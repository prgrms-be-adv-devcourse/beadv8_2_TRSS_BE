package com.backend.boundedContext.order.in.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 장바구니 담기 요청
 */
public record CartItemAddRequest(
        @NotNull @Positive Long optionId,
        @NotNull @Min(1) Integer quantity
) {
}
