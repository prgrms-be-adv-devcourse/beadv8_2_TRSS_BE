package com.backend.boundedContext.product.app.dto;

/**
 * 추천 상품 하나
 *
 * @param product 목록 카드
 * @param reason  추천 이유. 기준 상품과 이어지지 않은 추천(인기순 등)이면 null
 */
public record RecommendedProductDto(ProductSummaryDto product, RecommendationReasonDto reason) {
}
