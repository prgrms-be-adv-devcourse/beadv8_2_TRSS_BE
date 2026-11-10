package com.backend.boundedContext.review.out;

/** 별점 하나와 그 별점의 리뷰 수 (평점 요약 집계용) */
public record RatingCount(int rating, long count) {
}
