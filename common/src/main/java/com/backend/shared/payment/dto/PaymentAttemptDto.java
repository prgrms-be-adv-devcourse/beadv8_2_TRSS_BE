package com.backend.shared.payment.dto;

import java.time.LocalDateTime;

// 주문의 결제 시도 1건. 실패 시도도 포함하고, 실패 사유는 상태로만 알려 준다 (부족 금액은 담지 않음)
// attemptedAt: 결제 시도 시각, paidAt: 결제 완료 시각(PAID·REFUNDED만), refundedAt: 환불 시각(REFUNDED만)
public record PaymentAttemptDto(
        Long paymentId,
        PaymentAttemptStatus status,
        long amount,
        LocalDateTime attemptedAt,
        LocalDateTime paidAt,
        LocalDateTime refundedAt
) {
}
