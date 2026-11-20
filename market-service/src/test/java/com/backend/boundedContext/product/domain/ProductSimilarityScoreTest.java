package com.backend.boundedContext.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 비슷한 상품 점수: 텍스트 0.35 + 같은 소분류 0.2 + 가격 근접도 0.15 + 행동 점수 0.3 */
class ProductSimilarityScoreTest {

    private static final long SKINCARE = 10L;
    private static final long MAKEUP = 20L;
    private final ProductSimilarPolicy policy = new ProductSimilarPolicy(0.35, 0.2, 0.15, 0.3, 50);

    @Test
    @DisplayName("텍스트 점수는 후보 중 최고 점수로 나눠 0~1로 맞추고, 네 항목을 가중합한다")
    void weightedSumWithNormalizedText() {
        ProductSimilarCandidate best = new ProductSimilarCandidate(1, SKINCARE, 20_000, 0.5, 8.0);
        ProductSimilarCandidate half = new ProductSimilarCandidate(2, MAKEUP, 40_000, 0.0, 4.0);

        assertThat(ProductSimilarityScore.score(policy, best, 8.0, SKINCARE, 20_000))
                .isCloseTo(0.35 + 0.2 + 0.15 + 0.3 * 0.5, within(1e-12));
        assertThat(ProductSimilarityScore.score(policy, half, 8.0, SKINCARE, 20_000))
                .isCloseTo(0.35 * 0.5 + 0.15 * 0.5, within(1e-12));   // 다른 소분류, 가격 두 배
    }

    @Test
    @DisplayName("텍스트 점수는 기준 상품 자기 자신의 점수로 나눈다: 한 단어만 겹치는 약한 일치는 유일한 후보여도 만점이 아니다")
    void textNormalizedByReference() {
        ProductSimilarCandidate weak = new ProductSimilarCandidate(1, MAKEUP, 20_000, 0.0, 2.0);

        List<Long> ranked = ProductSimilarityScore.rank(policy, new ProductSimilarCandidates(List.of(weak,
                new ProductSimilarCandidate(2, SKINCARE, 20_000, 0.0, 0)), 10.0), SKINCARE, 20_000);

        assertThat(ProductSimilarityScore.score(policy, weak, 10.0, SKINCARE, 20_000))
                .isCloseTo(0.35 * 0.2 + 0.15, within(1e-12));
        assertThat(ranked).containsExactly(2L, 1L);   // 같은 소분류(0.2 + 0.15)가 약한 텍스트 일치(0.07 + 0.15)보다 앞
        // 기준 점수보다 높은 후보(짧은 문서의 길이 보정 등)도 1을 넘지 않는다
        assertThat(ProductSimilarityScore.score(policy, new ProductSimilarCandidate(3, MAKEUP, 20_000, 0.0, 12.0),
                10.0, SKINCARE, 20_000)).isCloseTo(0.35 + 0.15, within(1e-12));
    }

    @Test
    @DisplayName("가격 근접도는 작은 가격/큰 가격이라 싼 쪽과 비싼 쪽을 같게 보고, 가격이 0 이하면 0이다")
    void priceClosenessIsRatio() {
        assertThat(ProductSimilarityScore.priceCloseness(10_000, 20_000)).isEqualTo(0.5);
        assertThat(ProductSimilarityScore.priceCloseness(40_000, 20_000)).isEqualTo(0.5);
        assertThat(ProductSimilarityScore.priceCloseness(20_000, 20_000)).isEqualTo(1.0);
        assertThat(ProductSimilarityScore.priceCloseness(0, 20_000)).isZero();
    }

    @Test
    @DisplayName("텍스트로 찾은 후보가 없으면(모두 0) 같은 소분류·가격·행동 점수로만 정한다")
    void noTextCandidates() {
        List<Long> ranked = ProductSimilarityScore.rank(policy, ProductSimilarCandidates.withoutText(List.of(
                new ProductSimilarCandidate(1, MAKEUP, 20_000, 0.5, 0),
                new ProductSimilarCandidate(2, SKINCARE, 20_000, 0.1, 0))), SKINCARE, 20_000);

        assertThat(ranked).containsExactly(2L, 1L);   // 0.2 + 0.15 + 0.03 > 0.15 + 0.15
    }

    @Test
    @DisplayName("행동 점수가 높으면 텍스트가 조금 덜 비슷해도 앞에 오고, 점수가 같으면 최근 등록(ID가 큰) 상품이 앞이다")
    void behaviorAndTieBreak() {
        List<Long> ranked = ProductSimilarityScore.rank(policy, new ProductSimilarCandidates(List.of(
                new ProductSimilarCandidate(1, SKINCARE, 20_000, 0.0, 10.0),
                new ProductSimilarCandidate(2, SKINCARE, 20_000, 1.0, 8.0),
                new ProductSimilarCandidate(3, SKINCARE, 20_000, 0.0, 10.0)), 10.0), SKINCARE, 20_000);

        assertThat(ranked).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("가중치 합이 1이 아니거나 음수가 있으면 정책을 만들 수 없다 (기동 실패)")
    void invalidWeights() {
        assertThatThrownBy(() -> new ProductSimilarPolicy(0.5, 0.2, 0.15, 0.3, 50)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductSimilarPolicy(1.2, -0.2, 0.0, 0.0, 50)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductSimilarPolicy(0.35, 0.2, 0.15, 0.3, 0)).isInstanceOf(IllegalStateException.class);
    }
}
