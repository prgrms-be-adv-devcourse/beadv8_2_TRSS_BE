package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 상품 등록 요청. multipart의 request 파트(application/json)로 받는다.
 *
 * @param name        상품명
 * @param price       가격 (원)
 * @param categoryId  소분류 ID (사용 중인 소분류만 가능)
 * @param description 상품 설명
 * @param stock       초기 재고. 기본 옵션의 재고가 된다
 */
public record ProductCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Long price,
        @NotNull Long categoryId,
        @NotBlank String description,
        @NotNull @Positive Integer stock
) {
}
