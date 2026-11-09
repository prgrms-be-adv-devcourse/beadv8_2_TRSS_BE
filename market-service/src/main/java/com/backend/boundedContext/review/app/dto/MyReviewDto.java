package com.backend.boundedContext.review.app.dto;

import java.time.LocalDateTime;

/**
 * 내 리뷰 목록의 한 건
 *
 * @param reviewId     리뷰 ID
 * @param orderItemId  리뷰를 쓴 주문 항목 ID
 * @param productId    상품 ID
 * @param productName  상품명 (현재 값. 삭제된 상품도 이름을 준다. 상품 정보를 찾지 못하면 null)
 * @param thumbnailUrl 대표 이미지 URL. 없으면 null
 * @param optionName   구매한 옵션명 (주문 시점)
 * @param rating       별점 (1~5)
 * @param content      내용
 * @param modified     작성 후 수정했는지
 * @param createdAt    작성 시각
 */
public record MyReviewDto(
        Long reviewId,
        Long orderItemId,
        Long productId,
        String productName,
        String thumbnailUrl,
        String optionName,
        int rating,
        String content,
        boolean modified,
        LocalDateTime createdAt
) {
}
