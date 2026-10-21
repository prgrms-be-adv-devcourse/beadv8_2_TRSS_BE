package com.backend.boundedContext.order.in.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * 주문 생성 요청
 *
 * @param cartItemIds        주문할 장바구니 항목 ID
 * @param expectedTotalPrice 예상 결제금액
 */
public record OrderCreateRequest(
        @NotEmpty List<@NotNull @Positive Long> cartItemIds,
        @NotNull @PositiveOrZero Long expectedTotalPrice
) {
}