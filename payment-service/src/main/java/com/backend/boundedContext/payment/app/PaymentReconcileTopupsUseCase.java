package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGateways;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PROCESSING 결과 확인(대사): PG 응답 지연·서버 반영 실패로 PROCESSING에 남은 충전 건을 결제한 PG에 조회해 최종 결과를 반영
 * PG 조회는 트랜잭션 밖에서 하고, 반영·실패 기록은 PaymentTopupConfirmProcessor의 트랜잭션 메서드를 재사용
 * (승인 응답과 동시에 반영하려 해도 충전 건 잠금과 PROCESSING 확인으로 한 번만 반영됨)
 *
 * PG 결과별 처리
 * - APPROVED(승인) → complete(): 예치금 반영. 허용하지 않은 결제 수단이면 반영하지 않고 PG 전액 취소
 * - NOT_APPROVED(중단·만료) → FAILED_PG_DECLINED (실패 사유에 PG 원래 상태값을 남겨 중단·만료를 구분)
 * - CANCELED(PG에서 취소됨) → FAILED_SYSTEM_ERROR (예치금 반영 없이 돈이 돌려졌으므로)
 * - 결제 없음 → FAILED_SYSTEM_ERROR (PG에 결제 내역이 없어 돈이 빠지지 않음)
 * - 결과 모름(응답 없음·그 외 오류)·PENDING(진행 중 등) → PROCESSING 유지, 다음 회차에 다시 확인
 *
 * 대상은 TopupReconcileBatches로 배치 단위로 가져오며, 배치를 다 채우면 같은 회차에서 이어서 처리함 (결과 모름이 나오면 그 배치까지만)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileTopupsUseCase {

    private final PaymentPolicy paymentPolicy;
    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final TopupGateways topupGateways;
    private final PaymentTopupMethodCanceller paymentTopupMethodCanceller;

    public TopupReconcileResult reconcileProcessingTopups(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(paymentPolicy.topupReconcileDelay());
        int batchSize = paymentPolicy.topupReconcileBatchSize();
        return TopupReconcileBatches.run(batchSize,
                after -> paymentTopupConfirmProcessor.findReconcileTargets(cutoff, after, batchSize),
                this::reconcile,
                (target, e) -> log.error("충전 결과 확인 중 오류가 발생했습니다. 다음 회차에 다시 확인합니다. topupId={}", target.topupId(), e));
    }

    private TopupReconcileOutcome reconcile(TopupReconcileTarget target) {
        PgPaymentResult result;
        try {
            result = topupGateways.get(target.pgProvider()).getPayment(target.paymentKey());
        } catch (PgPaymentNotFoundException e) {
            paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_SYSTEM_ERROR, "PG에 결제 내역이 없어 승인되지 않은 것으로 처리했습니다.");
            return TopupReconcileOutcome.FAILED;
        } catch (PgResultUnknownException e) {
            // 회차마다 반복될 수 있어 스택 대신 가장 안쪽 원인 메시지만 남김
            log.warn("PG 결제 조회 결과를 알 수 없어 다음 회차에 다시 확인합니다. topupId={}, pg={}, 원인={}", target.topupId(), target.pgProvider(), NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return TopupReconcileOutcome.UNKNOWN;
        }

        switch (result.status()) {
            case APPROVED -> {
                if (!PaymentTopupConfirmProcessor.isAllowedMethod(result.method())) {
                    paymentTopupMethodCanceller.cancelApproved(target.topupId(), result.method());
                    return TopupReconcileOutcome.FAILED;
                }
                paymentTopupConfirmProcessor.complete(target.topupId(), result);
                return TopupReconcileOutcome.COMPLETED;
            }
            case NOT_APPROVED -> {
                paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_PG_DECLINED, "PG에서 결제가 승인되지 않았습니다. (" + result.pgStatus() + ")");
                return TopupReconcileOutcome.FAILED;
            }
            case CANCELED -> {
                log.error("예치금 반영 전에 PG에서 취소된 충전 건입니다. topupId={}, pg={}", target.topupId(), target.pgProvider());
                paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_SYSTEM_ERROR, "PG에서 취소된 결제입니다.");
                return TopupReconcileOutcome.FAILED;
            }
            default -> {
                log.warn("PG 결제가 아직 확정되지 않아 다음 회차에 다시 확인합니다. topupId={}, pg={}", target.topupId(), target.pgProvider());
                return TopupReconcileOutcome.PENDING;
            }
        }
    }
}
