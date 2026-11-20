package com.backend.boundedContext.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 행동 점수: 찜·판매는 상한 log 정규화, 별점은 베이지안 평균 × 신뢰도, 가중합 (가중치 0.4·0.4·0.2, 찜 상한 1,000, 판매 상한 500, m 4.0, C 5) */
class ProductBehaviorScoreTest {

    private final ProductBehaviorPolicy policy = policy(0.4, 0.4, 0.2);

    @Test
    @DisplayName("활동이 없으면 모든 항목과 행동 점수가 0이다 (리뷰가 없으면 별점 사전값이 기본 점수가 되지 않는다)")
    void zeroWithoutActivity() {
        ProductBehaviorScore score = ProductBehaviorScore.of(policy, 0, 0, 0, 0);

        assertThat(score.ratingScore()).isZero();
        assertThat(score.behaviorScore()).isZero();
    }

    @Test
    @DisplayName("찜·판매는 log(1+값)/log(1+상한)이고 상한 이상이면 1이다")
    void logScaleWithCap() {
        assertThat(ProductBehaviorScore.logScale(1_000, 1_000)).isEqualTo(1.0);
        assertThat(ProductBehaviorScore.logScale(5_000, 1_000)).isEqualTo(1.0);
        assertThat(ProductBehaviorScore.logScale(10, 1_000)).isCloseTo(Math.log(11) / Math.log(1_001), within(1e-12));
        assertThat(ProductBehaviorScore.logScale(10, 1_000)).isGreaterThan(ProductBehaviorScore.logScale(9, 1_000));
    }

    @Test
    @DisplayName("별점: 리뷰가 적으면 사전값으로 당기고 신뢰도를 곱한다. 3점 하나도 리뷰 없는 상품보다 높고, 같은 평균이면 리뷰가 많을수록 높다")
    void ratingWithConfidence() {
        double oneThreeStar = ProductBehaviorScore.ratingScore(policy, 1, 3);       // (5·4 + 3) / 6 = 3.83 → 0.708 × 1/6
        double oneFiveStar = ProductBehaviorScore.ratingScore(policy, 1, 5);
        double tenFiveStars = ProductBehaviorScore.ratingScore(policy, 10, 50);

        assertThat(oneThreeStar).isCloseTo(((5 * 4.0 + 3) / 6 - 1) / 4 * (1.0 / 6), within(1e-12));
        assertThat(oneThreeStar).isPositive();
        assertThat(oneFiveStar).isGreaterThan(oneThreeStar);
        assertThat(tenFiveStars).isGreaterThan(oneFiveStar);
        assertThat(tenFiveStars).isLessThan(1.0);
    }

    @Test
    @DisplayName("행동 점수는 가중합이고 모든 항목이 1이면 1이다")
    void weightedSum() {
        ProductBehaviorScore score = ProductBehaviorScore.of(policy, 1_000, 0, 0, 500);

        assertThat(score.behaviorScore()).isCloseTo(0.4 + 0.2, within(1e-12));   // 찜 1, 별점 0, 판매 1
        assertThat(score.wishCount()).isEqualTo(1_000);
        assertThat(score.salesCount()).isEqualTo(500);
    }

    @Test
    @DisplayName("가중치 합이 1이 아니면 정책을 만들 수 없다 (기동 실패)")
    void weightsMustSumToOne() {
        assertThatThrownBy(() -> policy(0.5, 0.4, 0.2))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ProductBehaviorPolicy policy(double wish, double rating, double sales) {
        return new ProductBehaviorPolicy(wish, rating, sales, 1_000, 500, 4.0, 5, 30, 60, 30, 5);
    }
}
