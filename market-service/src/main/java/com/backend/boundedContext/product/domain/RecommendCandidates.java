package com.backend.boundedContext.product.domain;

import java.util.List;
import java.util.Map;

/**
 * 개인화 추천 후보와 기준 상품별 텍스트 기준값
 *
 * @param candidates      후보 (기준 상품·뺄 상품 제외)
 * @param referenceScores 기준 상품 ID → 그 기준 상품 자기 자신의 텍스트 점수 (완전 일치). 기준 상품이 색인에 없으면 키가 없다
 * @param degraded        ES를 쓸 수 없어 DB 후보로 대신했는지. 텍스트·행동 점수가 없는 결과라 저장하지 않는다
 *                        (engine=jpa처럼 DB가 원래 구현이면 false)
 */
public record RecommendCandidates(List<RecommendCandidate> candidates, Map<Long, Double> referenceScores, boolean degraded) {

    public RecommendCandidates(List<RecommendCandidate> candidates, Map<Long, Double> referenceScores) {
        this(candidates, referenceScores, false);
    }

    public static RecommendCandidates withoutText(List<RecommendCandidate> candidates) {
        return new RecommendCandidates(candidates, Map.of(), false);
    }

    /** ES 대신 쓴 결과로 표시한다 */
    public RecommendCandidates asDegraded() {
        return new RecommendCandidates(candidates, referenceScores, true);
    }
}
