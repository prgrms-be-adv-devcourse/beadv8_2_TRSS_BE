package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 주문 결제 결과
 *
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