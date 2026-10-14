package com.backend.boundedContext.product.domain;

/**
 * 상품 검색 정렬. 값이 같으면 항상 상품 ID 내림차순으로 이어 정렬해서 페이지 사이에 중복·누락이 없게 한다.
 */
public enum ProductSort {
    LATEST,      // 최신 등록순 (기본)
    PRICE_ASC,   // 낮은 가격순
    PRICE_DESC   // 높은 가격순
}
