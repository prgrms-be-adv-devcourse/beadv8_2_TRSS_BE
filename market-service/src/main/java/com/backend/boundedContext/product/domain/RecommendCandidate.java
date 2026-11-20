package com.backend.boundedContext.product.domain;

import java.util.Map;

/**
 * 개인화 추천 후보 하나. 검색 구현이 찾아 주고, 점수는 {@link RecommendScore}가 매긴다.
 *
 * @param productId      상품 ID
 * @param categoryId     소분류 ID
 * @param effectivePrice 지금 기본가 (할인 중이면 할인가)
 * @param behaviorScore  행동 점수 (0~1). DB로 대신할 때는 0
 * @param textScores     기준 상품 ID → 그 기준 상품 텍스트와의 유사도 (검색 엔진 원래 점수). 겹치는 말이 없는 기준 상품은 키가 없다
 */
public record RecommendCandidate(
        long productId,
        long categoryId,
        long effectivePrice,
        double behaviorScore,
        Map<Long, Double> textScores
) {
}
