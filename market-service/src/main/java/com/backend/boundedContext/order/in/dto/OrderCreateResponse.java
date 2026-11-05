package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.Order;

import java.time.LocalDateTime;

/**
 * 주문 생성 응답
 *
 * @param orderId     주문 ID
 * @param orderNumber 주문번호
 * @param totalPrice  주문 금액
 * @param expiresAt   결제 만료 시각
 */
public record OrderCreateResponse(
        Long orderId,
        String orderNumber,
        long totalPrice,
        LocalDateTime expiresAt
) {
    public static OrderCreateResponse from(Order order) {
        return new OrderCreateResponse(order.getId(), order.getOrderNumber(), order.getTotalPrice(), order.getExpiresAt());
    }
}