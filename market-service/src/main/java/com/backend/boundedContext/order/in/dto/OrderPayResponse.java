package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 주문 결제 결과
 *
 * @param paidAmount 결제 금액 (주문의 payAmount, 쿠폰 할인 후·배송비 포함)
 * @param paymentId 결제 건 ID
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