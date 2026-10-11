package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * @param name      소분류 이름
 * @param parentId  대분류 ID
 * @param sortOrder 정렬 순서. 생략하면 마지막 순서
 */
public record CategoryCreateRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull Long parentId,
        @PositiveOrZero Integer sortOrder
) {
}
