package com.backend.boundedContext.product.out;

/** 소분류별 상품 수 ({@link ProductRepository#countByCategory}) */
public record CategoryProductCount(
        Long categoryId,
        long productCount
) {
}
