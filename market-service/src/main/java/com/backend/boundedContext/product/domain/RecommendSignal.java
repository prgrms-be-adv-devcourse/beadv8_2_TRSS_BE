package com.backend.boundedContext.product.domain;

import java.time.LocalDateTime;

/**
 * 회원 행동 하나 (기준 상품 후보)
 *
 * @param productId 상품 ID
 * @param basis     출처
 * @param at        행동 시각 (오래될수록 가중치가 줄어든다)
 */
public record RecommendSignal(long productId, RecommendBasis basis, LocalDateTime at) {
}
