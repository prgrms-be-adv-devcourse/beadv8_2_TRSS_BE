package com.backend.boundedContext.product.app.dto;

import java.util.List;

/**
 * 판매자 상품 수정 폼을 채우는 값
 *
 * @param thumbnail    대표 이미지. 이미지 기능 전까지 null
 * @param detailImages 상세 이미지 (순서대로). 이미지 기능 전까지 빈 목록
 */
public record SellerProductFormDto(
        Long productId,
        String name,
        long price,
        String description,
        Long parentCategoryId,
        Long categoryId,
        ProductImageDto thumbnail,
        List<ProductImageDto> detailImages
) {
}
