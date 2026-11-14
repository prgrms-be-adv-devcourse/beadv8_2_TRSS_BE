package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGateways;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CANCELING 결과 확인(대사): PG 취소 응답을 못 받았거나 반영에 실패해 CANCELING에 남은 충전 건을 결제한 PG에 조회해 최종 결과를 반영
 * PG 조회는 트랜잭션 밖에서 하고, 반영은 PaymentTopupCancelProcessor의 트랜잭션 메서드를 재사용
 * (취소 응답과 동시에 반영하려 해도 잠금과 회차 확인으로 한 번만 반영됨)
 *
 * PG 상태값이 아니라 취소된 금액 합계로 판단함 (토스는 부분 취소를 여러 번 해서 전부 취소돼도 PARTIAL_CANCELED로 남음)
 * - PG 취소 합계 = 이전 취소 금액 + 이번 취소 금액 → 이번 취소가 반영됨 → complete(): 취소 확정
 * - PG 취소 합계 = 이전 취소 금액 → 이번 취소가 반영되지 않음 → fail(): 잔액·남은 금액 되돌림
 * - 그 외(더 많거나 사이 값)·결제 없음 → 자동으로 정할 수 없어 CANCELING 유지, 오류 로그
 * - 결과 모름(응답 없음·그 외 오류) → CANCELING 유지, 다음 회차에 다시 확인
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileCancelingTopupsUseCase {

    private final PaymentPolicy paymentPolicy;
    private final PaymentTopupCancelProcessor paymentTopupCancelProcessor;
    private final TopupGateways topupGateways;

    /**
     * 확인 대기 시간(topupReconcileDelay)이 지나도록 CANCELING인 충전 건을 오래된 순으로 topupReconcileBatchSize건씩 확인함 (배치를 다 채우면 이어서 확인, 결과 모름이 나오면 그 배치까지만)
     * 대기 시간을 두는 이유: 취소 API가 아직 PG 응답을 기다리는 건을 대사가 먼저 판단해 되돌린 뒤 PG가 취소를 처리하면 이중 환불이 됨
     * (같은 회차라 회차 확인으로는 막지 못함) 그래서 PG 응답 대기 시간(30초)보다 충분히 긴 대기 시간이 지난 건만 봄
     * 반환값은 건수 집계 (completed: 취소 확정, failed: 되돌림, pending: 다음 회차에 다시 확인, errors: 예상 못 한 오류)
     */
    public TopupReconcileResult reconcileCancelingTopups(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(paymentPolicy.topupReconcileDelay());
        int batchSize = paymentPolicy.topupReconcileBatchSize();
        return TopupReconcileBatches.run(batchSize,
                after -> paymentTopupCancelProcessor.findReconcileTargets(cutoff, after, batchSize),
                this::reconcile,
                (target, e) -> log.error("충전 취소 결과 확인 중 오류가 발생했습니다. 다음 회차에 다시 확인합니다. topupId={}", target.topupId(), e));
    }

    /**
     * 한 건 확인: PG에 결제키로 조회한 취소 합계를 우리가 기록한 금액과 비교해 이번 회차의 결과를 정함
     * 예: 30,000원 충전 건에서 1회차에 20,000원 확정(이전 취소), 2회차에 10,000원 요청 중(이번 취소)
     * - PG 취소 합계 30,000 → 2회차 취소가 처리됨 → 확정
     * - PG 취소 합계 20,000 → 2회차 요청이 PG에 닿지 않았거나 처리되지 않음 → 되돌림
     * - PG 취소 합계 25,000·35,000 → 어느 쪽도 아님 → 그대로 두고 오류 로그
     * 확정·되돌림은 회차(attempt)를 넘겨, 그사이 취소 응답이 먼저 반영했거나 다음 회차가 시작됐으면 아무것도 하지 않게 함
     */
    private TopupReconcileOutcome reconcile(TopupCancelReconcileTarget target) {
        PgPaymentResult result;
        try {
            result = topupGateways.get(target.pgProvider()).getPayment(target.paymentKey());
        } catch (PgPaymentNotFoundException e) {
            // CANCELING은 PG가 승인한 결제를 취소하는 중이라, 정상이면 PG에 결제가 있음 (취소돼도 결제는 남고 상태만 바뀜). 되돌리면 이중 환불이 될 수 있어 사람이 확인
            log.error("취소 중인 충전 건의 결제가 PG에 없습니다. 확인이 필요합니다. topupId={}, pg={}", target.topupId(), target.pgProvider());
            return TopupReconcileOutcome.PENDING;
        } catch (PgResultUnknownException e) {
            // 회차마다 반복될 수 있어 스택 대신 가장 안쪽 원인 메시지만 남김
            log.warn("PG 결제 조회 결과를 알 수 없어 다음 회차에 다시 확인합니다. topupId={}, pg={}, 원인={}", target.topupId(), target.pgProvider(), NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return TopupReconcileOutcome.UNKNOWN;
        }

        // PG는 취소 요청 하나를 통째로 처리하거나 처리하지 않고, 같은 멱등 키로는 한 번만 처리함
        // 그래서 우리 요청만 있었다면 PG 취소 합계는 이전 취소 + 이번 취소이거나 이전 취소 그대로임
        if (result.canceledAmount() == target.canceledAmount() + target.cancelingAmount()) {
            paymentTopupCancelProcessor.complete(target.topupId(), target.attempt(), target.cancelingAmount());
            return TopupReconcileOutcome.COMPLETED;
        }
        // 이전 취소 금액과 정확히 같을 때만 되돌림. 조금이라도 처리됐는데 되돌리면 PG 환불과 잔액 복원이 둘 다 일어나 이중 환불이 됨
        if (result.canceledAmount() == target.canceledAmount()) {
            paymentTopupCancelProcessor.fail(target.topupId(), target.attempt());
            return TopupReconcileOutcome.FAILED;
        }
        // 더 많거나 사이 값이면 우리가 요청하지 않은 취소(예: 상점관리자에서 직접 취소)가 섞인 것 (이번 취소가 처리됐는지 확실하지 않아, 잘못 확정·되돌려 금액이 어긋나지 않게 사람이 확인)
        log.error("PG 취소 금액이 예상과 달라 자동으로 확정하지 않습니다. 확인이 필요합니다. topupId={}, PG 취소 합계 {}원, 이전 취소 {}원, 이번 취소 {}원", target.topupId(), result.canceledAmount(), target.canceledAmount(), target.cancelingAmount());
        return TopupReconcileOutcome.PENDING;
    }
}
