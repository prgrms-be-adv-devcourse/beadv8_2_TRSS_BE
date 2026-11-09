package com.backend.boundedContext.review.in.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 리뷰 작성
 *
 * @param orderItemId 리뷰를 쓸 주문 항목 ID (본인의 구매 확정 주문)
 * @param rating      별점 (1~5)
 * @param content     내용 (10~1,000자)
 */
public record ReviewCreateRequest(
        @NotNull Long orderItemId,
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotBlank @Size(min = 10, max = 1_000) String content
) {
}
