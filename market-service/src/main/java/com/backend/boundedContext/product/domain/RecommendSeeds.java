package com.backend.boundedContext.product.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 회원 행동에서 기준 상품 고르기 */
public final class RecommendSeeds {

    private RecommendSeeds() {
    }

    /**
     * 행동마다 출처 가중치 × 최근성 감쇠(2^(-지난 일수/반감기))를 매기고, 같은 상품은 가장 큰 가중치 하나만 남겨
     * 가중치 큰 순으로 정렬한다. 낮게 평가한 상품은 다른 출처로 와도 기준에서 뺀다.
     */
    public static List<RecommendSeed> fromSignals(ProductRecommendPolicy policy, Collection<RecommendSignal> signals,
                                                  Collection<Long> lowRatedProductIds, LocalDateTime now) {
        Map<Long, RecommendSeed> best = new HashMap<>();
        for (RecommendSignal signal : signals) {
            if (lowRatedProductIds.contains(signal.productId())) continue;
            double weight = policy.weightOf(signal.basis()) * decay(policy, signal.at(), now);
            best.merge(signal.productId(), new RecommendSeed(signal.productId(), signal.basis(), weight),
                    (a, b) -> a.weight() >= b.weight() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(RecommendSeed::weight).reversed()
                        .thenComparing(Comparator.comparingLong(RecommendSeed::productId).reversed()))
                .toList();
    }

    /** 최근 본 상품 (최근 순). 앞일수록 크게: 1, 1/2, 1/3 ... 같은 ID가 다시 오면 앞의 것만 쓴다 */
    public static List<RecommendSeed> fromRecentViews(List<Long> recentViewIds) {
        List<RecommendSeed> seeds = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (Long id : recentViewIds) {
            if (id == null || seen.contains(id)) continue;
            seen.add(id);
            seeds.add(new RecommendSeed(id, RecommendBasis.RECENT_VIEW, 1.0 / seen.size()));
        }
        return seeds;
    }

    /** 2^(-지난 일수/반감기). 미래 시각(시계 차이)은 1 */
    static double decay(ProductRecommendPolicy policy, LocalDateTime at, LocalDateTime now) {
        if (at == null || !at.isBefore(now)) return 1.0;
        double days = Duration.between(at, now).toMinutes() / (24.0 * 60);
        return Math.pow(2, -days / policy.getHalfLifeDays());
    }
}
