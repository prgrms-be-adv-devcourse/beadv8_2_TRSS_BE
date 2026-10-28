package com.backend.boundedContext.product.app.dto;

/**
 * 상품 검색 결과의 한 줄
 *
 * @param businessName 판매자 상호명
 * @param thumbnailUrl 대표 이미지. 이미지 기능 전까지 null
 * @param soldOut        삭제되지 않은 옵션의 재고가 모두 0이면 true
 * @param remainingStock 삭제되지 않은 옵션의 재고 합계가 1~5개일 때만 그 수, 그 외(6개 이상·품절)에는 null
 */
public record ProductSummaryDto(
        Long productId,
        String name,
        long price,
        Long categoryId,
        Long sellerId,
        String businessName,
        String thumbnailUrl,
        boolean soldOut,
        Integer remainingStock
) {
}
