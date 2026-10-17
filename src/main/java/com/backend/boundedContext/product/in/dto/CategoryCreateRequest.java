package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 추가한 소분류는 같은 대분류의 맨 뒤에 붙는다.
 *
 * @param name     소분류 이름
 * @param parentId 대분류 ID
 */
public record CategoryCreateRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull Long parentId
) {
}
