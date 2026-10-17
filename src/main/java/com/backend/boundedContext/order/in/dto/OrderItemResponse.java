package com.backend.boundedContext.order.in.dto;

/**
 * 주문 상세의 항목 한 줄 (주문 시점 스냅샷)
 */
public record OrderItemResponse(
        Long orderItemId,
        Long productId,
        Long optionId,
        String productName,
        String optionName,
        String sellerName,
        long unitPrice,
        int quantity,
        long lineAmount
) {
}