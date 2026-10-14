package com.backend.boundedContext.product.app.dto;

import com.backend.global.page.PageResponse;

/**
 * 판매자 상품 목록과 탭별 개수
 *
 * @param products 상태 필터를 적용한 목록
 * @param counts   상태 필터와 관계없이 센 탭별 개수 (상품명 검색어는 적용)
 */
public record SellerProductListDto(
        PageResponse<SellerProductSummaryDto> products,
        Counts counts
) {
    public record Counts(
            long all,
            long onSale,
            long soldOut,
            long stopped
    ) {
    }
}
