package com.backend.boundedContext.product.app.dto;

import java.util.List;

/**
 * 상품 상세
 *
 * @param businessName 판매자 상호명
 * @param thumbnailUrl 대표 이미지. 이미지 기능 전까지 null
 * @param imageUrls    상세 이미지. 이미지 기능 전까지 빈 목록
 * @param soldOut      옵션이 모두 품절이면 true
 * @param options      삭제되지 않은 옵션 (ID 순)
 */
public record ProductDetailDto(
        Long productId,
        String name,
        String description,
        long price,
        Long parentCategoryId,
        String parentCategoryName,
        Long categoryId,
        String categoryName,
        Long sellerId,
        String businessName,
        String thumbnailUrl,
        List<String> imageUrls,
        boolean soldOut,
        List<ProductOptionDto> options
) {
}
