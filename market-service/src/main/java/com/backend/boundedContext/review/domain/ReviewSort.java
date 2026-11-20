package com.backend.boundedContext.review.domain;

import org.springframework.data.domain.Sort;

/**
 * 상품 리뷰 목록 정렬. 별점이 같으면 리뷰 ID 내림차순(최신순)으로 이어 정렬해서 페이지 사이에 중복·누락이 없게 한다.
 */
public enum ReviewSort {
    LATEST(Sort.by(Sort.Order.desc("id"))),                                   // 최신순 (기본)
    RATING_HIGH(Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("id"))),   // 별점 높은순
    RATING_LOW(Sort.by(Sort.Order.asc("rating"), Sort.Order.desc("id")));     // 별점 낮은순

    private final Sort sort;

    ReviewSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
