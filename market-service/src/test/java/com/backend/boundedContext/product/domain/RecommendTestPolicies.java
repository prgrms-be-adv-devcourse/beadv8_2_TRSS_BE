package com.backend.boundedContext.product.domain;

/** 테스트용 추천 설정 (application.yaml의 시작값과 같다) */
final class RecommendTestPolicies {

    private RecommendTestPolicies() {
    }

    static ProductRecommendPolicy defaults() {
        return new ProductRecommendPolicy(1.2, 1.0, 0.7, 0.5, 4, 2, 30, 180, 5,
                0.35, 0.2, 0.15, 0.3, 30, 50, 3, 50, 3, 20, 60, 7, 20);
    }
}
