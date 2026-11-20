package com.backend.boundedContext.order.in.dto;

/**
 * 주문 상세의 항목 한 줄 (주문 시점 스냅샷)
 *
 * @param unitPrice    주문 단가 (할인 중이었으면 할인가 + 추가금). 금액은 이 값 기준
 * @param listPrice    주문 시점 정가 단가 (상품 가격 + 추가금). 할인이 없었으면 unitPrice와 같다. 표시용(취소선)
 * @param discountRate 주문 시점 할인율 (%). 할인 중이 아니었으면 null. 표시용이고 (listPrice − unitPrice)로 다시 계산하지 않는다
 * @param thumbnailUrl 상품의 현재 대표 이미지 URL (스냅샷 아님). 상품이 없어졌거나 이미지가 없거나 조회에 실패하면 null
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
        long lineAmount,
        long discountAmount,   // 상품 쿠폰 할인액 (쿠폰이 붙지 않은 항목은 0)
        long listPrice,
        Integer discountRate,
        String thumbnailUrl
) {
}