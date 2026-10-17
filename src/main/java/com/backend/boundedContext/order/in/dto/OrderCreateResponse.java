package com.backend.boundedContext.order.in.dto;

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
}