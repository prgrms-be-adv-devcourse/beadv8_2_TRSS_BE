package com.backend.boundedContext.product.domain;

/**
 * 상품 검색 정렬. 값이 같으면 항상 상품 ID 내림차순으로 이어 정렬해서 페이지 사이에 중복·누락이 없게 한다.
 * 가격순은 지금 적용 중인 기본가(할인 중이면 할인가, 옵션 추가금 제외) 기준이다. DB 검색으로 대신할 때는 정가 기준이다.
 */
public enum ProductSort {
    RELEVANCE,   // 관련도순 (검색어가 있을 때 기본). 검색어가 없거나 DB 검색이면 최신순과 같다
    LATEST,      // 최신 등록순 (검색어가 없을 때 기본)
    PRICE_ASC,   // 낮은 가격순
    PRICE_DESC;  // 높은 가격순

    /** 요청한 정렬(null이면 기본값)을 검색어 유무에 맞춰 정한다. 검색어가 없으면 관련도가 없으므로 최신순이다 */
    public static ProductSort resolve(ProductSort requested, boolean hasKeyword) {
        if (requested == null) return hasKeyword ? RELEVANCE : LATEST;
        if (requested == RELEVANCE && !hasKeyword) return LATEST;
        return requested;
    }
}
