package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 주문 결제 결과
 *
 * @param status     PAID(결제 완료) 또는 PAYING(결제 확인 중: 결과를 아직 모름. 서버가 확인해 PAID·PAYMENT_FAILED로 바꾼다)
 * @param paidAmount 결제 금액 (주문의 payAmount, 쿠폰 할인 후·배송비 포함)
 * @param paidAt     결제 완료 시각. PAYING이면 null
 * @param paymentId 결제 건 ID. PAYING이면 null
 */
public record OrderPayResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        long paidAmount,
        LocalDateTime paidAt,
        Long paymentId
) {
}