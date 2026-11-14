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
 * METHOD_CANCELING 결과 확인(대사): 허용하지 않은 결제 수단이라 PG 전액 취소를 요청했지만 끝나지 않은 충전 건을 결제한 PG에 조회해 마무리
 * 돈은 빠졌는데 예치금에도 반영되지 않은 상태라, 취소가 안 됐으면 다시 요청해 끝까지 돌려줌
 *
 * PG 취소 합계로 판단함 (상태값은 부분 취소 여부에 따라 달라서 보지 않음)
 * - 취소 합계 = 충전 금액 → 이미 전액 취소됨 → FAILED_METHOD_NOT_ALLOWED 확정
 * - 취소 합계 = 0 → 취소 안 됨 → 회차를 늘려 다시 취소 (최대 MAX_ATTEMPTS회까지. 마지막 회차가 실패하면 오류 로그 한 번)
 * - 그 외(일부만 취소)·PG에 결제 없음 → 자동으로 정할 수 없어 그대로 두고 오류 로그
 * - 결과 모름(응답 없음·그 외 오류) → 다음 회차에 다시 확인
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileMethodCancelingTopupsUseCase {

    // PG 전액 취소를 시도하는 최대 횟수 (승인 응답·대사의 첫 시도 포함). 넘으면 다시 요청하지 않고 사람이 확인
    static final int MAX_ATTEMPTS = 3;

    private final PaymentPolicy paymentPolicy;
    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final PaymentTopupMethodCanceller paymentTopupMethodCanceller;
    private final TopupGateways topupGateways;

    /**
     * 확인 대기 시간(topupReconcileDelay)이 지나도록 METHOD_CANCELING인 충전 건을 오래된 순으로 topupReconcileBatchSize건씩 확인함 (배치를 다 채우면 이어서 확인, 결과 모름이 나오면 그 배치까지만)
     * 재시도하면 충전 건이 갱신돼 다음 재시도도 확인 대기 시간 뒤에 함
     * 반환값은 건수 집계 (completed: 취소 확정, failed: 다시 요청했지만 실패, pending: 다음 회차에 다시 확인·사람 확인 대기, errors: 예상 못 한 오류)
     */
    public TopupReconcileResult reconcileMethodCancelingTopups(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(paymentPolicy.topupReconcileDelay());
        int batchSize = paymentPolicy.topupReconcileBatchSize();
        return TopupReconcileBatches.run(batchSize,
                after -> paymentTopupConfirmProcessor.findMethodCancelTargets(cutoff, after, batchSize),
                this::reconcile,
                (target, e) -> log.error("허용하지 않은 결제 수단 취소 결과 확인 중 오류가 발생했습니다. 다음 회차에 다시 확인합니다. topupId={}", target.topupId(), e));
    }

    private TopupReconcileOutcome reconcile(TopupMethodCancelTarget target) {
        PgPaymentResult result;
        try {
            result = topupGateways.get(target.pgProvider()).getPayment(target.paymentKey());
        } catch (PgPaymentNotFoundException e) {
            // PG가 승인한 결제라 정상이면 PG에 결제가 있음. 결제키·키 설정 등이 잘못된 것이라 사람이 확인
            log.error("허용하지 않은 결제 수단으로 승인된 충전 건의 결제가 PG에 없습니다. 확인이 필요합니다. topupId={}, pg={}", target.topupId(), target.pgProvider());
            return TopupReconcileOutcome.PENDING;
        } catch (PgResultUnknownException e) {
            // 회차마다 반복될 수 있어 스택 대신 가장 안쪽 원인 메시지만 남김
            log.warn("PG 결제 조회 결과를 알 수 없어 다음 회차에 다시 확인합니다. topupId={}, pg={}, 원인={}", target.topupId(), target.pgProvider(), NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return TopupReconcileOutcome.UNKNOWN;
        }

        // 이미 전액 취소됨 (앞선 요청의 응답만 못 받은 경우)
        if (result.canceledAmount() == target.amount()) {
            paymentTopupConfirmProcessor.completeMethodCancel(target.topupId(), target.attempt(), PaymentTopupMethodCanceller.completedReason(result.method()));
            return TopupReconcileOutcome.COMPLETED;
        }

        // 일부만 취소됐으면 전액 취소를 다시 보낼 수 없어(금액이 맞지 않음) 사람이 확인
        if (result.canceledAmount() != 0) {
            log.error("허용하지 않은 결제 수단 충전 건의 PG 취소 금액이 충전 금액과 달라 자동으로 처리하지 않습니다. 확인이 필요합니다. topupId={}, PG 취소 합계 {}원, 충전 금액 {}원",
                    target.topupId(), result.canceledAmount(), target.amount());
            return TopupReconcileOutcome.PENDING;
        }

        // 취소되지 않음 → 최대 횟수 전이면 다시 요청
        if (target.attempt() >= MAX_ATTEMPTS) {
            // 마지막 회차 실패 때 오류 로그를 이미 남겼으므로 여기서는 경고만 (알림이 반복되지 않게)
            log.warn("허용하지 않은 결제 수단 충전 건의 PG 취소가 {}회 실패해 다시 요청하지 않습니다. 확인 대기 중입니다. topupId={}", MAX_ATTEMPTS, target.topupId());
            return TopupReconcileOutcome.PENDING;
        }
        if (paymentTopupMethodCanceller.retry(target.topupId(), target.attempt(), result.method())) {
            return TopupReconcileOutcome.COMPLETED;
        }
        if (target.attempt() + 1 >= MAX_ATTEMPTS) {
            log.error("허용하지 않은 결제 수단 충전 건의 PG 취소가 {}회 모두 실패했습니다. 돈이 빠진 채 반영되지 않았으니 확인이 필요합니다. topupId={}", MAX_ATTEMPTS, target.topupId());
        }
        return TopupReconcileOutcome.FAILED;
    }
}
