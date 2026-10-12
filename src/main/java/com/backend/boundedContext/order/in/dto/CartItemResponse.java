package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.UnorderableReason;

/**
 * 장바구니 조회 응답의 항목 하나. 구매 단위는 옵션이다.
 */
public record CartItemResponse(
        Long itemId,
        Long optionId,
        Long productId,
        String productName,
        String optionName,
        String thumbnailUrl,
        long price,
        int quantity,
        long lineAmount,
        boolean selected,
        boolean orderable,
        UnorderableReason unorderableReason
) {
}
