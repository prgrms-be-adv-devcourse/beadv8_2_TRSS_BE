package com.backend.shared.review.dto;

import java.time.LocalDateTime;

/**
 * 회원이 상품에 준 별점 하나 (ReviewApi.getMemberRatings, 삭제한 리뷰 제외)
 *
 * @param productId 상품 ID
 * @param rating    별점 (1~5)
 * @param ratedAt   별점을 마지막으로 정한 시각 (리뷰 수정 시각)
 */
public record MemberRatingDto(Long productId, int rating, LocalDateTime ratedAt) {
}
