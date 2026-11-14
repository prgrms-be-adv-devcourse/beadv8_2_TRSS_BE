package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.UnorderableReason;

/**
 * 장바구니 조회 응답의 항목 하나. 구매 단위는 옵션이다.
 *
 * @param price        현재 단가 (할인 중이면 할인가 + 추가금). 금액은 이 값 기준
 * @param listPrice    현재 정가 단가 (상품 가격 + 추가금). 할인이 없으면 price와 같다. 표시용(취소선). 옵션 정보가 없으면 0
 * @param discountRate 현재 할인율 (%). 할인 중이 아니면 null. 표시용이고 (listPrice − price)로 다시 계산하지 않는다
 */
public record CartItemResponse(
        Long itemId,
        Long optionId,
        Long productId,
        String sellerName,
        String productName,
        String optionName,
        String thumbnailUrl,
        long price,
        long listPrice,
        Integer discountRate,
        int quantity,
        long lineAmount,
        boolean selected,
        boolean orderable,
        UnorderableReason unorderableReason
) {
}
