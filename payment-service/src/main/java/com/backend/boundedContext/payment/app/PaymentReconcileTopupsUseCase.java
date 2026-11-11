package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.List;

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
 * - APPROVED(승인) → complete(): 예치금 반영
 * - NOT_APPROVED(중단·만료) → FAILED_PG_DECLINED
 * - CANCELED(PG에서 취소됨) → FAILED_SYSTEM_ERROR (예치금 반영 없이 돈이 돌려졌으므로)
 * - 결제 없음 → FAILED_SYSTEM_ERROR (승인 요청이 PG에 닿지 않음)
 * - 결과 모름(응답 없음·그 외 오류)·PENDING(진행 중 등) → PROCESSING 유지, 다음 회차에 다시 확인
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileTopupsUseCase {

    private final PaymentPolicy paymentPolicy;
    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final TopupGateways topupGateways;

    public TopupReconcileResult reconcileProcessingTopups(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(paymentPolicy.topupReconcileDelay());
        List<TopupReconcileTarget> targets = paymentTopupConfirmProcessor.findReconcileTargets(cutoff, paymentPolicy.topupReconcileBatchSize());

        int completed = 0, failed = 0, pending = 0, errors = 0;
        for (TopupReconcileTarget target : targets) {
            // 건별로 잡아 한 건의 오류가 나머지 처리를 멈추지 않게 함
            try {
                switch (reconcile(target)) {
                    case COMPLETED -> completed++;
                    case FAILED -> failed++;
                    case PENDING -> pending++;
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("충전 결과 확인 중 오류가 발생했습니다. 다음 회차에 다시 확인합니다. topupId={}", target.topupId(), e);
            }
        }
        return new TopupReconcileResult(completed, failed, pending, errors);
    }

    private Outcome reconcile(TopupReconcileTarget target) {
        PgPaymentResult result;
        try {
            result = topupGateways.get(target.pgProvider()).getPaymentByOrderId(target.orderId());
        } catch (PgPaymentNotFoundException e) {
            paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_SYSTEM_ERROR, "PG에 결제 내역이 없어 승인되지 않은 것으로 처리했습니다.");
            return Outcome.FAILED;
        } catch (PgResultUnknownException e) {
            // 회차마다 반복될 수 있어 스택 대신 가장 안쪽 원인 메시지만 남김
            log.warn("PG 결제 조회 결과를 알 수 없어 다음 회차에 다시 확인합니다. topupId={}, pg={}, 원인={}", target.topupId(), target.pgProvider(), NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return Outcome.PENDING;
        }

        switch (result.status()) {
            case APPROVED -> {
                paymentTopupConfirmProcessor.complete(target.topupId(), result);
                return Outcome.COMPLETED;
            }
            case NOT_APPROVED -> {
                paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_PG_DECLINED, "PG에서 결제가 승인되지 않았습니다.");
                return Outcome.FAILED;
            }
            case CANCELED -> {
                log.error("예치금 반영 전에 PG에서 취소된 충전 건입니다. topupId={}, pg={}", target.topupId(), target.pgProvider());
                paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_SYSTEM_ERROR, "PG에서 취소된 결제입니다.");
                return Outcome.FAILED;
            }
            default -> {
                log.warn("PG 결제가 아직 확정되지 않아 다음 회차에 다시 확인합니다. topupId={}, pg={}", target.topupId(), target.pgProvider());
                return Outcome.PENDING;
            }
        }
    }

    private enum Outcome { COMPLETED, FAILED, PENDING }
}
