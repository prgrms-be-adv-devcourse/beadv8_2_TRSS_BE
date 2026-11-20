package com.backend.boundedContext.product.domain;

/**
 * 상품 하나의 행동 지표와 행동 점수. 색인할 때 DB에서 다시 계산해 덮어쓴다(이벤트마다 쌓지 않는다).
 * <ul>
 *   <li>찜·판매: log(1 + 값) / log(1 + 상한), 상한을 넘으면 1. 전체 최대값이 아니라 상한으로 나눠 상품 하나만 다시 색인해도 점수가 맞다</li>
 *   <li>별점: 베이지안 평균 (C·m + 별점 합) / (C + 리뷰 수)를 1~5에서 0~1로 맞추고 신뢰도 리뷰 수 / (리뷰 수 + C)를 곱한다.
 *       신뢰도를 곱하지 않으면 리뷰 없는 상품이 사전값 m만큼 기본 점수를 받아, 리뷰를 받은 상품이 오히려 낮아질 수 있다</li>
 *   <li>행동 점수 = 가중치로 더한 값 (0~1)</li>
 * </ul>
 *
 * @param wishCount     찜 수
 * @param reviewCount   리뷰 수 (삭제 제외)
 * @param ratingScore   별점 항목 (0~1, 리뷰가 없으면 0)
 * @param salesCount    최근 판매량 (결제 수량, 취소·반품 제외)
 * @param behaviorScore 행동 점수 (0~1)
 */
public record ProductBehaviorScore(long wishCount, long reviewCount, double ratingScore, long salesCount, double behaviorScore) {

    private static final double MIN_RATING = 1.0;
    private static final double RATING_RANGE = 4.0;   // 별점 1~5

    public static ProductBehaviorScore of(ProductBehaviorPolicy policy, long wishCount, long reviewCount, long ratingSum,
                                          long salesCount) {
        double rating = ratingScore(policy, reviewCount, ratingSum);
        double score = policy.getWishWeight() * logScale(wishCount, policy.getWishCap())
                + policy.getRatingWeight() * rating
                + policy.getSalesWeight() * logScale(salesCount, policy.getSalesCap());
        return new ProductBehaviorScore(wishCount, reviewCount, rating, salesCount, score);
    }

    static double logScale(long value, long cap) {
        if (value <= 0) return 0;
        return Math.min(1.0, Math.log1p(value) / Math.log1p(cap));
    }

    static double ratingScore(ProductBehaviorPolicy policy, long reviewCount, long ratingSum) {
        if (reviewCount <= 0) return 0;
        int priorCount = policy.getRatingPriorCount();
        double bayesian = (priorCount * policy.getRatingPriorMean() + ratingSum) / (priorCount + reviewCount);
        double normalized = (bayesian - MIN_RATING) / RATING_RANGE;
        double confidence = (double) reviewCount / (reviewCount + priorCount);
        return normalized * confidence;
    }
}
