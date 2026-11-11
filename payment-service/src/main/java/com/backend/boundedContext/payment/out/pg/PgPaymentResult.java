package com.backend.boundedContext.payment.out.pg;

import java.time.LocalDateTime;

/**
 * PG 결제 결과 중 충전에 필요한 값만 PG와 상관없는 형식으로 담음
 * approvedAt은 한국 시간(KST) 기준. 승인 전이면 null
 */
public record PgPaymentResult(
        String paymentKey,
        String orderId,
        PgPaymentStatus status,
        long totalAmount,
        PgPaymentMethod method,
        LocalDateTime approvedAt
) {
    public boolean isApproved() {
        return status == PgPaymentStatus.APPROVED;
    }
}
