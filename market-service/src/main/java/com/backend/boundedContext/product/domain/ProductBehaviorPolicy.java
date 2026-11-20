package com.backend.boundedContext.product.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 상품 행동 점수 정책 값 (custom.product.behavior). 인기순 정렬과 추천의 행동 점수에 쓴다.
 * 가중치 합이 1이 아니면 기동하지 않는다.
 */
@Component
@Getter
public class ProductBehaviorPolicy {
    private final double wishWeight;
    private final double ratingWeight;
    private final double salesWeight;
    private final long wishCap;
    private final long salesCap;
    private final double ratingPriorMean;
    private final int ratingPriorCount;
    private final int salesWindowDays;
    private final int metricsIntervalSeconds;
    private final int metricsOverlapSeconds;
    private final int metricsLookbackMinutes;

    public ProductBehaviorPolicy(
            @Value("${custom.product.behavior.wishWeight}") double wishWeight,
            @Value("${custom.product.behavior.ratingWeight}") double ratingWeight,
            @Value("${custom.product.behavior.salesWeight}") double salesWeight,
            @Value("${custom.product.behavior.wishCap}") long wishCap,
            @Value("${custom.product.behavior.salesCap}") long salesCap,
            @Value("${custom.product.behavior.ratingPriorMean}") double ratingPriorMean,
            @Value("${custom.product.behavior.ratingPriorCount}") int ratingPriorCount,
            @Value("${custom.product.behavior.salesWindowDays}") int salesWindowDays,
            @Value("${custom.product.behavior.metricsIntervalSeconds}") int metricsIntervalSeconds,
            @Value("${custom.product.behavior.metricsOverlapSeconds}") int metricsOverlapSeconds,
            @Value("${custom.product.behavior.metricsLookbackMinutes}") int metricsLookbackMinutes
    ) {
        if (Math.abs(wishWeight + ratingWeight + salesWeight - 1.0) > 1e-9) {
            throw new IllegalStateException("행동 점수 가중치의 합은 1이어야 합니다. wish=" + wishWeight
                    + ", rating=" + ratingWeight + ", sales=" + salesWeight);
        }
        if (wishCap < 1 || salesCap < 1 || ratingPriorCount < 1) {
            throw new IllegalStateException("행동 점수 상한·사전값은 1 이상이어야 합니다.");
        }
        this.wishWeight = wishWeight;
        this.ratingWeight = ratingWeight;
        this.salesWeight = salesWeight;
        this.wishCap = wishCap;
        this.salesCap = salesCap;
        this.ratingPriorMean = ratingPriorMean;
        this.ratingPriorCount = ratingPriorCount;
        this.salesWindowDays = salesWindowDays;
        this.metricsIntervalSeconds = metricsIntervalSeconds;
        this.metricsOverlapSeconds = metricsOverlapSeconds;
        this.metricsLookbackMinutes = metricsLookbackMinutes;
    }
}
