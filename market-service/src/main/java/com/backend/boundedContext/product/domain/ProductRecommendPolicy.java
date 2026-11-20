package com.backend.boundedContext.product.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 개인화 추천 설정 (custom.product.recommend.*) */
@Component
@Getter
public class ProductRecommendPolicy {
    // 기준 상품 출처별 가중치
    private final double highRatingWeight;
    private final double purchaseWeight;
    private final double wishWeight;
    private final double cartWeight;
    /** 이 별점 이상이면 높게 평가한 상품 (기준 상품) */
    private final int highRatingMin;
    /** 이 별점 이하면 낮게 평가한 상품 (기준에서 빼고 추천 결과에서도 뺀다) */
    private final int lowRatingMax;
    /** 행동 가중치 반감기(일) */
    private final double halfLifeDays;
    /** 이만큼(일) 전까지의 구매만 본다 */
    private final int purchaseDays;
    /** 기준 상품 최대 수 (가중치 상위) */
    private final int seedCount;

    // 점수 가중치 (합 1)
    private final double textWeight;
    private final double categoryWeight;
    private final double priceWeight;
    private final double behaviorWeight;

    /** 기준 상품 하나당 텍스트 후보 수 */
    private final int candidatePerSeed;
    /** 선호 소분류 후보 수 */
    private final int preferredCandidateSize;
    /** 한 목록에 같은 판매자 상품 최대 수 */
    private final int sellerMax;

    /** 회원님을 위한 추천을 저장할 때 남기는 수 (읽을 때 걸러지는 상품을 생각해 넉넉히) */
    private final int forYouStoreSize;
    /** 기준 상품별 섹션 수 */
    private final int sectionCount;
    /** 섹션 하나에 저장할 상품 수 */
    private final int sectionStoreSize;
    /** 저장한 결과가 이만큼(분) 지나면 돌려준 뒤 뒤에서 다시 계산한다 (이벤트가 없는 장바구니·주문 취소를 맞춘다) */
    private final int staleMinutes;
    /** 저장한 결과의 TTL(일) */
    private final int ttlDays;
    /** 최근 본 상품으로 받는 최대 ID 수 */
    private final int recentViewMax;

    public ProductRecommendPolicy(
            @Value("${custom.product.recommend.highRatingWeight}") double highRatingWeight,
            @Value("${custom.product.recommend.purchaseWeight}") double purchaseWeight,
            @Value("${custom.product.recommend.wishWeight}") double wishWeight,
            @Value("${custom.product.recommend.cartWeight}") double cartWeight,
            @Value("${custom.product.recommend.highRatingMin}") int highRatingMin,
            @Value("${custom.product.recommend.lowRatingMax}") int lowRatingMax,
            @Value("${custom.product.recommend.halfLifeDays}") double halfLifeDays,
            @Value("${custom.product.recommend.purchaseDays}") int purchaseDays,
            @Value("${custom.product.recommend.seedCount}") int seedCount,
            @Value("${custom.product.recommend.textWeight}") double textWeight,
            @Value("${custom.product.recommend.categoryWeight}") double categoryWeight,
            @Value("${custom.product.recommend.priceWeight}") double priceWeight,
            @Value("${custom.product.recommend.behaviorWeight}") double behaviorWeight,
            @Value("${custom.product.recommend.candidatePerSeed}") int candidatePerSeed,
            @Value("${custom.product.recommend.preferredCandidateSize}") int preferredCandidateSize,
            @Value("${custom.product.recommend.sellerMax}") int sellerMax,
            @Value("${custom.product.recommend.forYouStoreSize}") int forYouStoreSize,
            @Value("${custom.product.recommend.sectionCount}") int sectionCount,
            @Value("${custom.product.recommend.sectionStoreSize}") int sectionStoreSize,
            @Value("${custom.product.recommend.staleMinutes}") int staleMinutes,
            @Value("${custom.product.recommend.ttlDays}") int ttlDays,
            @Value("${custom.product.recommend.recentViewMax}") int recentViewMax
    ) {
        if (Math.abs(textWeight + categoryWeight + priceWeight + behaviorWeight - 1.0) > 1e-9) {
            throw new IllegalStateException("추천 점수 가중치의 합은 1이어야 합니다. text=" + textWeight + ", category="
                    + categoryWeight + ", price=" + priceWeight + ", behavior=" + behaviorWeight);
        }
        if (highRatingWeight <= 0 || purchaseWeight <= 0 || wishWeight <= 0 || cartWeight <= 0) {
            throw new IllegalStateException("추천 기준 상품 출처 가중치는 0보다 커야 합니다.");
        }
        if (lowRatingMax >= highRatingMin) {
            throw new IllegalStateException("낮은 별점 상한은 높은 별점 하한보다 작아야 합니다.");
        }
        if (halfLifeDays <= 0 || seedCount < 1 || sellerMax < 1 || sectionCount < 0) {
            throw new IllegalStateException("추천 반감기·기준 상품 수·판매자당 최대 수는 0보다 커야 합니다.");
        }
        this.highRatingWeight = highRatingWeight;
        this.purchaseWeight = purchaseWeight;
        this.wishWeight = wishWeight;
        this.cartWeight = cartWeight;
        this.highRatingMin = highRatingMin;
        this.lowRatingMax = lowRatingMax;
        this.halfLifeDays = halfLifeDays;
        this.purchaseDays = purchaseDays;
        this.seedCount = seedCount;
        this.textWeight = textWeight;
        this.categoryWeight = categoryWeight;
        this.priceWeight = priceWeight;
        this.behaviorWeight = behaviorWeight;
        this.candidatePerSeed = candidatePerSeed;
        this.preferredCandidateSize = preferredCandidateSize;
        this.sellerMax = sellerMax;
        this.forYouStoreSize = forYouStoreSize;
        this.sectionCount = sectionCount;
        this.sectionStoreSize = sectionStoreSize;
        this.staleMinutes = staleMinutes;
        this.ttlDays = ttlDays;
        this.recentViewMax = recentViewMax;
    }

    /** 출처의 기본 가중치. 최근 본 상품은 순서로 따로 정한다 ({@link RecommendSeeds#fromRecentViews}) */
    public double weightOf(RecommendBasis basis) {
        return switch (basis) {
            case HIGH_RATING -> highRatingWeight;
            case PURCHASE -> purchaseWeight;
            case WISH -> wishWeight;
            case CART -> cartWeight;
            case RECENT_VIEW -> 1.0;
        };
    }
}
