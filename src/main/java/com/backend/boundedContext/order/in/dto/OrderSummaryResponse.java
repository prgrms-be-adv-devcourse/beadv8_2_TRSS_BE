package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 주문 목록의 한 줄
 *
 * @param representativeProductName 대표 상품명
 * @param itemCount                 주문 항목 수
 * @param orderedAt                 주문 일시
 * @param expiresAt                 결제 만료 시각
 */
public record OrderSummaryResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        long totalPrice,
        String representativeProductName,
        int itemCount,
        LocalDateTime orderedAt,
        LocalDateTime expiresAt
) {
}
