package com.backend.boundedContext.product.domain;

/**
 * 추천의 기준 상품
 *
 * @param productId 상품 ID
 * @param basis     가중치가 가장 큰 출처 (추천 이유)
 * @param weight    기준 가중치 (출처 가중치 × 최근성 감쇠)
 */
public record RecommendSeed(long productId, RecommendBasis basis, double weight) {
}
