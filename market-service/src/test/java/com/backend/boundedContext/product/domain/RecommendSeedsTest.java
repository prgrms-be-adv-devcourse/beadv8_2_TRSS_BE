package com.backend.boundedContext.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/** 기준 상품: 출처 가중치(높은 별점 1.2 > 구매 1.0 > 찜 0.7 > 장바구니 0.5) × 2^(-지난 일수/30) */
class RecommendSeedsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 12, 0);
    private final ProductRecommendPolicy policy = RecommendTestPolicies.defaults();

    @Test
    @DisplayName("출처 가중치에 최근성 감쇠를 곱하고 가중치 큰 순으로 정렬한다 (30일 지난 행동은 절반)")
    void weightsAndDecay() {
        List<RecommendSeed> seeds = RecommendSeeds.fromSignals(policy, List.of(
                new RecommendSignal(1, RecommendBasis.WISH, NOW),
                new RecommendSignal(2, RecommendBasis.PURCHASE, NOW.minusDays(30)),
                new RecommendSignal(3, RecommendBasis.HIGH_RATING, NOW),
                new RecommendSignal(4, RecommendBasis.CART, NOW.minusDays(1))), Set.of(), NOW);

        assertThat(seeds).extracting(RecommendSeed::productId).containsExactly(3L, 1L, 2L, 4L);
        assertThat(seeds.get(2).weight()).isCloseTo(0.5, within(1e-9));   // 구매 1.0 × 1/2
    }

    @Test
    @DisplayName("같은 상품이 여러 출처에 있으면 가중치가 가장 큰 출처 하나만 남긴다")
    void mergeSameProduct() {
        List<RecommendSeed> seeds = RecommendSeeds.fromSignals(policy, List.of(
                new RecommendSignal(1, RecommendBasis.WISH, NOW),
                new RecommendSignal(1, RecommendBasis.PURCHASE, NOW.minusDays(60)),   // 1.0 × 1/4 < 찜 0.7
                new RecommendSignal(2, RecommendBasis.CART, NOW.minusDays(1)),
                new RecommendSignal(2, RecommendBasis.PURCHASE, NOW)), Set.of(), NOW);

        assertThat(seeds).extracting(RecommendSeed::productId, RecommendSeed::basis)
                .containsExactly(tuple(2L, RecommendBasis.PURCHASE), tuple(1L, RecommendBasis.WISH));
    }

    @Test
    @DisplayName("낮게 평가한 상품은 찜·구매로 와도 기준에서 뺀다")
    void lowRatedExcluded() {
        List<RecommendSeed> seeds = RecommendSeeds.fromSignals(policy, List.of(
                new RecommendSignal(1, RecommendBasis.PURCHASE, NOW),
                new RecommendSignal(2, RecommendBasis.WISH, NOW)), Set.of(1L), NOW);

        assertThat(seeds).extracting(RecommendSeed::productId).containsExactly(2L);
    }

    @Test
    @DisplayName("최근 본 상품은 앞일수록 크게(1, 1/2, 1/3), 같은 ID가 다시 오면 앞의 것만 쓴다")
    void recentViews() {
        List<RecommendSeed> seeds = RecommendSeeds.fromRecentViews(java.util.Arrays.asList(5L, 7L, 5L, null, 9L));

        assertThat(seeds).extracting(RecommendSeed::productId).containsExactly(5L, 7L, 9L);
        assertThat(seeds).extracting(RecommendSeed::weight).containsExactly(1.0, 0.5, 1.0 / 3);
        assertThat(seeds).allSatisfy(seed -> assertThat(seed.basis()).isEqualTo(RecommendBasis.RECENT_VIEW));
    }

    @Test
    @DisplayName("점수 가중치 합이 1이 아니거나 낮은 별점 상한이 높은 별점 하한 이상이면 기동하지 않는다")
    void invalidPolicy() {
        assertThatThrownBy(() -> new ProductRecommendPolicy(1.2, 1.0, 0.7, 0.5, 4, 2, 30, 180, 5,
                0.5, 0.2, 0.15, 0.3, 30, 50, 3, 50, 3, 20, 60, 7, 20)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductRecommendPolicy(1.2, 1.0, 0.7, 0.5, 3, 3, 30, 180, 5,
                0.35, 0.2, 0.15, 0.3, 30, 50, 3, 50, 3, 20, 60, 7, 20)).isInstanceOf(IllegalStateException.class);
    }
}
