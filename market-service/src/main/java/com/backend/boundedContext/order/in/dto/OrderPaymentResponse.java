package com.backend.boundedContext.order.in.dto;

import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;

import java.time.LocalDateTime;

/**
 * 주문 상세의 결제 내역 한 줄
 *
 * @param status      결제 결과. 실패 사유는 상태(FAILED_*)로 알려 준다
 * @param attemptedAt 결제 시도 시각
 * @param paidAt      결제 완료 시각
 * @param refundedAt  환불 시각
 */
public record OrderPaymentResponse(
        Long paymentId,
        PaymentAttemptStatus status,
        long amount,
        LocalDateTime attemptedAt,
        LocalDateTime paidAt,
        LocalDateTime refundedAt
) {
    public static OrderPaymentResponse from(PaymentAttemptDto attempt) {
        return new OrderPaymentResponse(attempt.paymentId(), attempt.status(), attempt.amount(),
                attempt.attemptedAt(), attempt.paidAt(), attempt.refundedAt());
    }
}
