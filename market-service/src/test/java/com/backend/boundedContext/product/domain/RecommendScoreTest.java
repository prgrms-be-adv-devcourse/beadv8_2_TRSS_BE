package com.backend.boundedContext.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 개인화 추천 점수: 텍스트 0.35 + 선호 소분류 0.2 + 가격대 0.15 + 행동 0.3, 기준 상품 여러 개를 가중치로 합친다 */
class RecommendScoreTest {

    private static final long SKINCARE = 10L;
    private static final long MAKEUP = 20L;
    private final ProductRecommendPolicy policy = RecommendTestPolicies.defaults();

    /** 크림(스킨케어, 2만 원, 가중치 2)과 립(메이크업, 2만 원, 가중치 1) */
    private final List<RecommendSeedProduct> seeds = List.of(
            seed(1, RecommendBasis.PURCHASE, 2.0, SKINCARE, 20_000),
            seed(2, RecommendBasis.WISH, 1.0, MAKEUP, 20_000));

    @Test
    @DisplayName("텍스트는 기준 상품마다 자기 자신의 점수로 나눠 가중 평균하고, 선호 소분류는 같은 소분류 기준 상품의 가중치 비중이다")
    void weightedAcrossSeeds() {
        RecommendCandidates candidates = new RecommendCandidates(List.of(
                candidate(10, SKINCARE, 20_000, 0.0, Map.of(1L, 5.0, 2L, 2.0))),
                Map.of(1L, 10.0, 2L, 4.0));

        RecommendScore.Ranked ranked = RecommendScore.rank(policy, seeds, candidates).getFirst();

        double text = (2.0 * 0.5 + 1.0 * 0.5) / 3.0;   // 두 기준 모두 절반만큼 비슷하다
        double category = 2.0 / 3.0;                     // 스킨케어 기준 상품의 비중
        assertThat(ranked.score()).isCloseTo(0.35 * text + 0.2 * category + 0.15, within(1e-12));
        assertThat(ranked.reasonSeedId()).isEqualTo(1L);   // 기여가 큰 기준 상품(가중치 2 × 0.5)
    }

    @Test
    @DisplayName("추천 이유: 텍스트로 이어진 기준 상품이 없으면 같은 소분류에서 가장 무거운 기준 상품, 그것도 없으면 null")
    void reasonFallsBackToCategory() {
        RecommendCandidates candidates = RecommendCandidates.withoutText(List.of(
                candidate(10, MAKEUP, 20_000, 0.9, Map.of()),
                candidate(11, 30L, 20_000, 0.9, Map.of())));

        List<RecommendScore.Ranked> ranked = RecommendScore.rank(policy, seeds, candidates);

        assertThat(ranked).extracting(RecommendScore.Ranked::productId).containsExactly(10L, 11L);
        assertThat(ranked.get(0).reasonSeedId()).isEqualTo(2L);
        assertThat(ranked.get(1).reasonSeedId()).isNull();
    }

    @Test
    @DisplayName("기준값이 없는 기준 상품(색인에 없음)은 그 기준에 대한 후보 중 최고 점수로 나눈다")
    void missingReferenceUsesMax() {
        RecommendCandidates candidates = new RecommendCandidates(List.of(
                candidate(10, SKINCARE, 20_000, 0, Map.of(1L, 8.0)),
                candidate(11, SKINCARE, 20_000, 0, Map.of(1L, 4.0))), Map.of());

        assertThat(RecommendScore.textBases(seeds, candidates)).containsEntry(1L, 8.0).containsEntry(2L, 0.0);
    }

    @Test
    @DisplayName("기준 가격은 가중 중앙값이다: 가중치가 큰 기준 상품의 가격 쪽으로 정해진다")
    void weightedMedianPrice() {
        assertThat(RecommendScore.weightedMedianPrice(List.of(
                seed(1, RecommendBasis.PURCHASE, 3.0, SKINCARE, 50_000),
                seed(2, RecommendBasis.WISH, 1.0, SKINCARE, 10_000),
                seed(3, RecommendBasis.WISH, 1.0, SKINCARE, 20_000)))).isEqualTo(50_000);
    }

    @Test
    @DisplayName("기준 상품이 없으면 빈 목록")
    void noSeeds() {
        assertThat(RecommendScore.rank(policy, List.of(), RecommendCandidates.withoutText(List.of(
                candidate(10, SKINCARE, 20_000, 1.0, Map.of()))))).isEmpty();
    }

    private static RecommendSeedProduct seed(long id, RecommendBasis basis, double weight, long categoryId, long price) {
        return new RecommendSeedProduct(new RecommendSeed(id, basis, weight), categoryId, price);
    }

    private static RecommendCandidate candidate(long id, long categoryId, long price, double behavior, Map<Long, Double> text) {
        return new RecommendCandidate(id, categoryId, price, behavior, text);
    }
}
