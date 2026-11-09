package com.backend.boundedContext.review.app.dto;

import com.backend.boundedContext.review.domain.Review;

import java.time.LocalDateTime;

/**
 * 상품 상세의 리뷰 한 건
 *
 * @param reviewId     리뷰 ID
 * @param reviewerName 작성자 이름 (작성 시점, 가운데 글자는 *)
 * @param rating       별점 (1~5)
 * @param content      내용
 * @param optionName   구매한 옵션명 (주문 시점)
 * @param modified     작성 후 수정했는지
 * @param createdAt    작성 시각
 */
public record ProductReviewDto(
        Long reviewId,
        String reviewerName,
        int rating,
        String content,
        String optionName,
        boolean modified,
        LocalDateTime createdAt
) {
    public static ProductReviewDto from(Review review) {
        return new ProductReviewDto(review.getId(), review.getReviewerName(), review.getRating(), review.getContent(),
                review.getOptionName(), review.isModified(), review.getCreatedAt());
    }
}
