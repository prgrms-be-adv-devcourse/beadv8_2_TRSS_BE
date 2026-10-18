package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 주문 목록의 한 줄
 *
 * @param representativeProductName 대표 상품명
 * @param thumbnailUrl              대표 상품의 현재 대표 이미지 URL. 상품이 삭제됐거나 이미지가 없으면 null
 * @param itemCount                 주문 항목 수
 * @param orderedAt                 주문 일시
 * @param expiresAt                 결제 만료 시각
 * @param remainingSeconds          결제 가능 남은 시간(초)
 * @param paidAt                    결제 일시(결제 전이면 null)
 */
public record OrderSummaryResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        long totalPrice,
        String representativeProductName,
        String thumbnailUrl,
        int itemCount,
        LocalDateTime orderedAt,
        LocalDateTime expiresAt,
        long remainingSeconds,
        LocalDateTime paidAt
) {
}
