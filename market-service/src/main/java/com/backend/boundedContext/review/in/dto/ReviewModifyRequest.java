package com.backend.boundedContext.review.in.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 리뷰 수정. 보낸 값만 바꾸고, 둘 중 하나는 보내야 한다.
 *
 * @param rating  별점 (1~5)
 * @param content 내용 (10~1,000자, 공백만은 안 됨)
 */
public record ReviewModifyRequest(
        @Min(1) @Max(5) Integer rating,
        @Size(min = 10, max = 1_000) @Pattern(regexp = "(?s).*\\S.*") String content
) {
    @AssertTrue(message = "rating과 content 중 하나 이상을 보내야 합니다.")
    public boolean isAnyFieldPresent() {
        return rating != null || content != null;
    }
}
