package com.backend.boundedContext.product.domain;

/**
 * 점수 계산에 쓰는 기준 상품 정보
 *
 * @param seed       기준 상품과 가중치
 * @param categoryId 소분류 ID
 * @param price      지금 기본가 (할인 중이면 할인가)
 */
public record RecommendSeedProduct(RecommendSeed seed, long categoryId, long price) {

    public long productId() {
        return seed.productId();
    }

    public double weight() {
        return seed.weight();
    }
}
