package com.backend.boundedContext.product.domain;

/**
 * 상품 검색 정렬. 값이 같으면 항상 상품 ID 내림차순으로 이어 정렬해서 페이지 사이에 중복·누락이 없게 한다.
 * 가격순은 지금 적용 중인 기본가(할인 중이면 할인가, 옵션 추가금 제외) 기준이다. DB 검색으로 대신할 때는 정가 기준이다.
 */
public enum ProductSort {
    RELEVANCE,   // 관련도순 (검색어가 있을 때 기본). 검색어가 없거나 DB 검색이면 최신순과 같다
    LATEST,      // 최신 등록순 (검색어가 없을 때 기본)
    PRICE_ASC,   // 낮은 가격순
    PRICE_DESC,  // 높은 가격순
    DISCOUNT_RATE, // 할인율 높은순 (할인 없는 상품은 뒤, 같으면 최신순)
    POPULAR,     // 인기순 (행동 점수: 찜·별점·최근 30일 판매량). DB 검색이면 최신순과 같다
    WISH,        // 찜 많은순. DB 검색이면 최신순과 같다
    SALES;       // 최근 30일 판매량순. DB 검색이면 최신순과 같다

    /** 요청한 정렬(null이면 기본값)을 검색어 유무에 맞춰 정한다. 검색어가 없으면 관련도가 없으므로 최신순이다 */
    public static ProductSort resolve(ProductSort requested, boolean hasKeyword) {
        if (requested == null) return hasKeyword ? RELEVANCE : LATEST;
        if (requested == RELEVANCE && !hasKeyword) return LATEST;
        return requested;
    }
}
