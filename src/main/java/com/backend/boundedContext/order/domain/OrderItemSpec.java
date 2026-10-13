package com.backend.boundedContext.order.domain;

/**
 * 주문 생성 시점의 스냅샷
 *
 * @param cartItemId  원본 장바구니 항목 ID
 * @param productId   상품 ID
 * @param optionId    옵션 ID
 * @param sellerId    판매자 ID
 * @param productName 상품명 (스냅샷)
 * @param optionName  옵션명 (스냅샷)
 * @param sellerName  상호명 (스냅샷)
 * @param unitPrice   주문 단가 (스냅샷)
 * @param quantity    수량
 */
public record OrderItemSpec(
        Long cartItemId,
        Long productId,
        Long optionId,
        Long sellerId,
        String productName,
        String optionName,
        String sellerName,
        long unitPrice,
        int quantity
) {
}