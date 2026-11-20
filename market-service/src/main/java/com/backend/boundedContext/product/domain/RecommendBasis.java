package com.backend.boundedContext.product.domain;

/** 개인화 추천의 기준 상품 출처 (추천 이유에 쓴다) */
public enum RecommendBasis {
    /** 별점 4점 이상을 준 상품 */
    HIGH_RATING,
    /** 결제한 상품 */
    PURCHASE,
    /** 찜한 상품 */
    WISH,
    /** 장바구니에 담은 상품 */
    CART,
    /** 최근 본 상품 (프론트가 보낸 목록) */
    RECENT_VIEW
}
