package com.backend.boundedContext.product.app.dto;

import java.util.List;

/**
 * 판매자 상품 수정 폼을 채우는 값
 *
 * @param thumbnail    대표 이미지. 이미지 기능 전까지 null
 * @param detailImages 상세 이미지 (순서대로). 이미지 기능 전까지 빈 목록
 * @param optionNames  옵션 이름 (순서대로). 옵션이 없는 상품이면 빈 목록
 * @param options      삭제되지 않은 옵션 조합 (옵션 ID 순)
 * @param discounts    진행 중·예약 할인 (시작 시각 순, 끝난 할인은 빠짐)
 */
public record SellerProductFormDto(
        Long productId,
        String name,
        long price,
        String description,
        Long parentCategoryId,
        Long categoryId,
        ProductImageDto thumbnail,
        List<ProductImageDto> detailImages,
        List<String> optionNames,
        List<SellerOptionDto> options,
        List<SellerDiscountDto> discounts
) {
}
