package com.backend.shared.review.dto;

/**
 * 상품 하나의 리뷰 통계 (삭제한 리뷰 제외). 상품 컨텍스트가 목록·상세의 리뷰 수·평균 별점과 검색 색인의 별점 점수를 계산할 때 쓴다.
 *
 * @param productId   상품 ID
 * @param reviewCount 리뷰 수
 * @param ratingSum   별점 합 (별점은 1~5)
 */
public record ProductRatingStatsDto(Long productId, long reviewCount, long ratingSum) {

    /** 리뷰가 없는 상품 */
    public static ProductRatingStatsDto empty(Long productId) {
        return new ProductRatingStatsDto(productId, 0, 0);
    }

    /** 평균 별점 (소수 첫째 자리 반올림). 리뷰가 없으면 0.0. 상품 목록·상세·리뷰 평점 요약이 모두 이 값을 쓴다 */
    public double averageRating() {
        return reviewCount == 0 ? 0.0 : Math.round(ratingSum * 10.0 / reviewCount) / 10.0;
    }
}
