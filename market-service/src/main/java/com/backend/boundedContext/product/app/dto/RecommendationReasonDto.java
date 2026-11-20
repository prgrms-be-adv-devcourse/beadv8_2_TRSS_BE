package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.RecommendBasis;

/**
 * 추천 이유. "찜한 ○○와 비슷해요"처럼 보여 준다
 *
 * @param basis       기준 상품의 출처 (HIGH_RATING: 높게 평가한, PURCHASE: 구매한, WISH: 찜한, CART: 장바구니에 담은)
 * @param productId   기준 상품 ID
 * @param productName 기준 상품 이름
 */
public record RecommendationReasonDto(RecommendBasis basis, Long productId, String productName) {
}
