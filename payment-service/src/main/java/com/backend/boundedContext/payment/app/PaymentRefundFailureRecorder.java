package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 반품 환불 ③ 실패 기록: ②가 예외로 롤백된 뒤 새 트랜잭션에서 실패 상태와 사유를 남긴다.
 * 돈을 옮기다 막힌 경우는 사유별 FAILED_*로(사람이 확인), 그 밖의 예외는 일시 오류(FAILED_SYSTEM_ERROR)로 본다.
 * 일시 오류면 true를 돌려준다. 호출한 쪽이 예외를 다시 던져 Kafka가 같은 메시지를 다시 주게 한다(재시도)
 */
@Component
@RequiredArgsConstructor
public class PaymentRefundFailureRecorder {

    private final PaymentRefundRepository paymentRefundRepository;
    private final PaymentRepository paymentRepository;

    @Transactional
    public boolean record(Long refundId, RuntimeException cause) {
        PaymentRefund refund = paymentRefundRepository.findByIdForUpdate(refundId).orElseThrow();
        if (!refund.isProcessable()) return false;

        PaymentRefundStatus failStatus = failStatusOf(cause);
        // ②에서 찾은 결제 ID는 롤백으로 사라졌으므로 다시 찾는다. ②가 롤백돼 결제는 그대로 PAID라 같은 결제가 나온다 (없으면 null)
        Long paidPaymentId = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(refund.getOrderId()).stream()
                .filter(Payment::isPaid)
                .map(Payment::getId)
                .findFirst().orElse(null);
        refund.fail(failStatus, paidPaymentId, String.valueOf(cause));

        return failStatus == PaymentRefundStatus.FAILED_SYSTEM_ERROR && refund.isProcessable();
    }

    private static PaymentRefundStatus failStatusOf(RuntimeException cause) {
        if (cause instanceof DomainException e) {
            String resultCode = e.getResultCode();
            if (PaymentErrorCode.REFUND_HOLDING_SETTLED.resultCode().equals(resultCode)) {
                return PaymentRefundStatus.FAILED_HOLDING_SETTLED;
            }
            if (PaymentErrorCode.REFUND_PLATFORM_BALANCE_INSUFFICIENT.resultCode().equals(resultCode)) {
                return PaymentRefundStatus.FAILED_PLATFORM_BALANCE;
            }
        }
        return PaymentRefundStatus.FAILED_SYSTEM_ERROR;
    }
}
