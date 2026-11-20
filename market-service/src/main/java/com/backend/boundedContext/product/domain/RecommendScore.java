package com.backend.boundedContext.product.domain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 개인화 추천 점수. 기준 상품 여러 개를 가중치로 합친다 (각 항목 0~1).
 * <ul>
 *   <li>텍스트: 기준 상품마다 그 기준 상품 자기 자신의 점수로 나눠 0~1로 맞추고(1을 넘지 않게), 기준 가중치로 평균한다.
 *   기준값이 없으면(기준 상품이 색인에 없음) 그 기준 상품에 대한 후보 중 최고 점수로 대신한다</li>
 *   <li>선호 소분류: 기준 상품 가중치 중 후보와 같은 소분류인 기준 상품의 비중</li>
 *   <li>가격대: 기준 상품 가격의 가중 중앙값과의 비율 (작은 가격 / 큰 가격)</li>
 *   <li>행동: 색인한 행동 점수</li>
 * </ul>
 * 추천 이유는 텍스트 점수에 가장 많이 기여한 기준 상품이고, 텍스트로 이어진 기준 상품이 없으면 같은 소분류의 가장 무거운 기준 상품이다.
 */
public final class RecommendScore {

    private RecommendScore() {
    }

    /** 점수 내림차순(같으면 상품 ID 내림차순) */
    public static List<Ranked> rank(ProductRecommendPolicy policy, List<RecommendSeedProduct> seeds,
                                    RecommendCandidates candidates) {
        if (seeds.isEmpty()) return List.of();
        double totalWeight = seeds.stream().mapToDouble(RecommendSeedProduct::weight).sum();
        Map<Long, Double> textBases = textBases(seeds, candidates);
        long basePrice = weightedMedianPrice(seeds);

        return candidates.candidates().stream()
                .map(candidate -> score(policy, seeds, totalWeight, textBases, basePrice, candidate))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparing(Comparator.comparingLong(Ranked::productId).reversed()))
                .toList();
    }

    private static Ranked score(ProductRecommendPolicy policy, List<RecommendSeedProduct> seeds, double totalWeight,
                                Map<Long, Double> textBases, long basePrice, RecommendCandidate candidate) {
        double text = 0;
        double category = 0;
        Long textReason = null;
        double bestContribution = 0;
        Long categoryReason = null;
        double bestCategoryWeight = 0;
        for (RecommendSeedProduct seed : seeds) {
            double base = textBases.getOrDefault(seed.productId(), 0.0);
            double raw = candidate.textScores().getOrDefault(seed.productId(), 0.0);
            double contribution = base > 0 ? seed.weight() * Math.min(1.0, raw / base) : 0;
            text += contribution;
            if (contribution > bestContribution) {
                bestContribution = contribution;
                textReason = seed.productId();
            }
            if (seed.categoryId() == candidate.categoryId()) {
                category += seed.weight();
                if (seed.weight() > bestCategoryWeight) {
                    bestCategoryWeight = seed.weight();
                    categoryReason = seed.productId();
                }
            }
        }
        double score = policy.getTextWeight() * text / totalWeight
                + policy.getCategoryWeight() * category / totalWeight
                + policy.getPriceWeight() * ProductSimilarityScore.priceCloseness(candidate.effectivePrice(), basePrice)
                + policy.getBehaviorWeight() * Math.clamp(candidate.behaviorScore(), 0.0, 1.0);
        return new Ranked(candidate.productId(), score, textReason != null ? textReason : categoryReason);
    }

    /** 기준 상품별 텍스트 기준값: 자기 자신의 점수, 없으면 후보 중 최고 점수 */
    static Map<Long, Double> textBases(List<RecommendSeedProduct> seeds, RecommendCandidates candidates) {
        Map<Long, Double> bases = new HashMap<>();
        for (RecommendSeedProduct seed : seeds) {
            Double reference = candidates.referenceScores().get(seed.productId());
            double base = reference != null && reference > 0 ? reference
                    : candidates.candidates().stream()
                            .mapToDouble(c -> c.textScores().getOrDefault(seed.productId(), 0.0))
                            .max().orElse(0);
            bases.put(seed.productId(), base);
        }
        return bases;
    }

    /** 가중 중앙값: 가격 순으로 늘어놓고 가중치 누적이 절반에 닿는 기준 상품의 가격 */
    static long weightedMedianPrice(List<RecommendSeedProduct> seeds) {
        List<RecommendSeedProduct> byPrice = seeds.stream().sorted(Comparator.comparingLong(RecommendSeedProduct::price)).toList();
        double half = byPrice.stream().mapToDouble(RecommendSeedProduct::weight).sum() / 2;
        double cumulative = 0;
        for (RecommendSeedProduct seed : byPrice) {
            cumulative += seed.weight();
            if (cumulative >= half) return seed.price();
        }
        return byPrice.getLast().price();
    }

    /**
     * @param productId    후보 상품 ID
     * @param score        점수
     * @param reasonSeedId 추천 이유가 되는 기준 상품 ID. 이어진 기준 상품이 없으면(행동 점수·가격만으로 올라옴) null
     */
    public record Ranked(long productId, double score, Long reasonSeedId) {
    }
}
