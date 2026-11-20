package com.backend.boundedContext.product.domain;

import java.util.List;

/**
 * 비슷한 상품 후보와 텍스트 점수 기준값.
 *
 * @param candidates         후보 (기준 상품 제외)
 * @param referenceTextScore 기준 상품 자기 자신의 텍스트 점수 (같은 질의에서 기준 문서가 받은 점수, 완전 일치의 점수).
 *                           후보 텍스트 점수를 이 값으로 나눠 0~1로 맞춘다. 기준 상품이 색인에 없거나 텍스트로 찾지 않았으면 0
 * @param degraded           ES를 쓸 수 없어 DB 후보로 대신했는지 (engine=jpa처럼 DB가 원래 구현이면 false)
 */
public record ProductSimilarCandidates(List<ProductSimilarCandidate> candidates, double referenceTextScore, boolean degraded) {

    public ProductSimilarCandidates(List<ProductSimilarCandidate> candidates, double referenceTextScore) {
        this(candidates, referenceTextScore, false);
    }

    public static ProductSimilarCandidates withoutText(List<ProductSimilarCandidate> candidates) {
        return new ProductSimilarCandidates(candidates, 0, false);
    }

    /** ES 대신 쓴 결과로 표시한다 */
    public ProductSimilarCandidates asDegraded() {
        return new ProductSimilarCandidates(candidates, referenceTextScore, true);
    }
}
