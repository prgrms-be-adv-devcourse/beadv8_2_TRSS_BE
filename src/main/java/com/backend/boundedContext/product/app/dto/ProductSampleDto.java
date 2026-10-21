package com.backend.boundedContext.product.app.dto;

/**
 * 개발용 샘플 상품 한 건.
 *
 * @param parentCategoryName 대분류 이름
 * @param categoryName       소분류 이름
 * @param name               상품명
 * @param price              가격 (원)
 * @param stock              초기 재고
 */
public record ProductSampleDto(
        String parentCategoryName,
        String categoryName,
        String name,
        long price,
        int stock
) {
}
