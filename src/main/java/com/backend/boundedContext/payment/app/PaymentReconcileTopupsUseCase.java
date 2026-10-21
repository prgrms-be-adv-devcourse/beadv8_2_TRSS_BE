package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.toss.TossPaymentException;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;
import com.backend.boundedContext.payment.out.toss.TossTimeoutException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PROCESSING 결과 확인(대사): 토스 응답 지연·서버 반영 실패로 PROCESSING에 남은 충전 건을 토스에 조회해 최종 결과를 반영
 * 토스 조회는 트랜잭션 밖에서 하고, 반영·실패 기록은 PaymentTopupConfirmProcessor의 트랜잭션 메서드를 재사용
 * (승인 응답과 동시에 반영하려 해도 충전 건 잠금과 PROCESSING 확인으로 한 번만 반영됨)
 *
 * 토스 결과별 처리
 * - DONE(승인) → complete(): 예치금 반영
 * - ABORTED·EXPIRED(승인 안 됨) → FAILED_PG_DECLINED
 * - CANCELED(토스에서 취소됨) → FAILED_SYSTEM_ERROR (예치금 반영 없이 돈이 돌려졌으므로)
 * - 결제 없음(404 NOT_FOUND_PAYMENT) → FAILED_SYSTEM_ERROR (승인 요청이 토스에 닿지 않음)
 * - 응답 없음·그 외 오류·진행 중(IN_PROGRESS 등) → PROCESSING 유지, 다음 회차에 다시 확인
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileTopupsUseCase {

    private static final Set<String> NOT_APPROVED_STATUSES = Set.of("ABORTED", "EXPIRED");
    private static final String CANCELED_STATUS = "CANCELED";
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";

    private final PaymentPolicy paymentPolicy;
    private final PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;
    private final TossPaymentsClient tossPaymentsClient;

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
        TossPaymentResponse toss;
        try {
            toss = tossPaymentsClient.getPaymentByOrderId(target.orderId());
        } catch (TossTimeoutException e) {
            log.warn("토스 결제 조회 응답이 없어 다음 회차에 다시 확인합니다. topupId={}", target.topupId());
            return Outcome.PENDING;
        } catch (TossPaymentException e) {
            if (e.getHttpStatus() == 404 && NOT_FOUND_PAYMENT.equals(e.getCode())) {
                paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_SYSTEM_ERROR, "토스에 결제 내역이 없어 승인되지 않은 것으로 처리했습니다.");
                return Outcome.FAILED;
            }
            log.warn("토스 결제 조회에 실패해 다음 회차에 다시 확인합니다. topupId={}, status={}, code={}", target.topupId(), e.getHttpStatus(), e.getCode());
            return Outcome.PENDING;
        }

        if (toss.isApproved()) {
            paymentTopupConfirmProcessor.complete(target.topupId(), toss);
            return Outcome.COMPLETED;
        }
        if (NOT_APPROVED_STATUSES.contains(toss.status())) {
            paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_PG_DECLINED, "토스에서 결제가 승인되지 않았습니다. (" + toss.status() + ")");
            return Outcome.FAILED;
        }
        if (CANCELED_STATUS.equals(toss.status())) {
            log.error("예치금 반영 전에 토스에서 취소된 충전 건입니다. topupId={}", target.topupId());
            paymentTopupConfirmProcessor.fail(target.topupId(), TopupStatus.FAILED_SYSTEM_ERROR, "토스에서 취소된 결제입니다.");
            return Outcome.FAILED;
        }
        log.warn("토스 결제가 아직 확정되지 않아 다음 회차에 다시 확인합니다. topupId={}, status={}", target.topupId(), toss.status());
        return Outcome.PENDING;
    }

    private enum Outcome { COMPLETED, FAILED, PENDING }
}
