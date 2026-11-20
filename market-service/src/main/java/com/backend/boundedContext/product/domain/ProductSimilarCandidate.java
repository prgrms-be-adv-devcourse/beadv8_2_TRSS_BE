package com.backend.boundedContext.product.domain;

/**
 * 비슷한 상품 후보 하나. 검색 구현이 찾아 주고, 점수는 {@link ProductSimilarityScore}가 매긴다.
 *
 * @param productId      상품 ID
 * @param categoryId     소분류 ID
 * @param effectivePrice 지금 기본가 (할인 중이면 할인가). DB 검색으로 대신할 때는 정가
 * @param behaviorScore  행동 점수 (0~1). DB 검색으로 대신할 때는 0
 * @param textScore      기준 상품과의 텍스트 유사도 (검색 엔진의 원래 점수, 0 이상). 텍스트로 찾지 않은 후보는 0
 */
public record ProductSimilarCandidate(
        long productId,
        long categoryId,
        long effectivePrice,
        double behaviorScore,
        double textScore
) {
}
