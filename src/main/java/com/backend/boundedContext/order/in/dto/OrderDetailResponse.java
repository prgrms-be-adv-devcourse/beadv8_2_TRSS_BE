package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 상세
 *
 * @param remainingSeconds 결제 가능 남은 시간(초)
 */
public record OrderDetailResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        long totalPrice,
        LocalDateTime orderedAt,
        LocalDateTime expiresAt,
        long remainingSeconds,
        LocalDateTime paidAt,
        LocalDateTime canceledAt,
        LocalDateTime confirmedAt,
        List<OrderItemResponse> items
) {
}