package com.backend.boundedContext.review.out;

/** 상품 하나의 별점 하나와 그 별점의 리뷰 수 (평점 요약·별점 점수 집계용) */
public record RatingCount(Long productId, int rating, long count) {
}
