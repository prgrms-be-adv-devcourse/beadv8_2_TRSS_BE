package com.backend.boundedContext.review.app.dto;

import java.util.Map;

/**
 * 상품 평점 요약 (삭제된 리뷰 제외)
 *
 * @param averageRating 평균 별점. 소수 첫째 자리에서 반올림 (리뷰가 없으면 0.0)
 * @param reviewCount   리뷰 수
 * @param ratingCounts  별점별 리뷰 수. 5점부터 1점 순서이고 리뷰가 없는 별점도 0으로 준다
 */
public record ReviewSummaryDto(
        double averageRating,
        long reviewCount,
        Map<Integer, Long> ratingCounts
) {
}
