package com.backend.boundedContext.product.domain;

import java.util.Comparator;
import java.util.List;

/**
 * 비슷한 상품 점수. 속성(텍스트·카테고리·가격)과 행동 점수를 0~1로 맞춰 가중합한다.
 * <ul>
 *   <li>텍스트: 기준 상품 자기 자신의 점수(완전 일치)로 나누고 1을 넘지 않게 한다. 검색 엔진 점수는 질의마다 크기가 달라
 *   그대로 더할 수 없는데, 후보 중 최고 점수로 나누면 한 단어만 겹치는 약한 일치도 유일한 후보면 1이 되기 때문이다.
 *   기준값이 없으면(기준 상품이 아직 색인에 없음) 후보 중 최고 점수로 대신한다</li>
 *   <li>카테고리: 기준 상품과 같은 소분류면 1 (후보는 같은 대분류 안에서만 찾는다)</li>
 *   <li>가격: 작은 가격 / 큰 가격. 절반이나 두 배면 0.5다 (금액 차이가 아니라 비율로 본다)</li>
 *   <li>행동: 색인한 행동 점수 그대로</li>
 * </ul>
 */
public final class ProductSimilarityScore {

    private ProductSimilarityScore() {
    }

    /** 후보를 점수 내림차순(같으면 상품 ID 내림차순, 최근 등록 우선)으로 정렬한 상품 ID */
    public static List<Long> rank(ProductSimilarPolicy policy, ProductSimilarCandidates candidates,
                                  long baseCategoryId, long basePrice) {
        double textBase = candidates.referenceTextScore() > 0
                ? candidates.referenceTextScore()
                : candidates.candidates().stream().mapToDouble(ProductSimilarCandidate::textScore).max().orElse(0);
        return candidates.candidates().stream()
                .map(c -> new Scored(c.productId(), score(policy, c, textBase, baseCategoryId, basePrice)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(Comparator.comparingLong(Scored::productId).reversed()))
                .map(Scored::productId)
                .toList();
    }

    static double score(ProductSimilarPolicy policy, ProductSimilarCandidate candidate, double textBase,
                        long baseCategoryId, long basePrice) {
        double text = textBase > 0 ? Math.min(1.0, candidate.textScore() / textBase) : 0;
        double category = candidate.categoryId() == baseCategoryId ? 1 : 0;
        double behavior = Math.clamp(candidate.behaviorScore(), 0.0, 1.0);
        return policy.getTextWeight() * text
                + policy.getCategoryWeight() * category
                + policy.getPriceWeight() * priceCloseness(candidate.effectivePrice(), basePrice)
                + policy.getBehaviorWeight() * behavior;
    }

    /** 두 가격의 비율 (작은 쪽 / 큰 쪽, 0~1). 가격이 0 이하면 0 */
    static double priceCloseness(long price, long basePrice) {
        if (price <= 0 || basePrice <= 0) return 0;
        return (double) Math.min(price, basePrice) / Math.max(price, basePrice);
    }

    private record Scored(long productId, double score) {
    }
}
