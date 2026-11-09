package com.backend.boundedContext.order.in.dto;

import jakarta.validation.Valid;
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
 * @param shippingAddress    배송지. 프론트가 붙기 전까지 선택 값(생략하면 배송 없이 결제 기준으로 구매 확정되는 주문). 보내면 형식을 검증한다
 */
public record OrderCreateRequest(
        @NotEmpty List<@NotNull @Positive Long> cartItemIds,
        @NotNull @PositiveOrZero Long expectedTotalPrice,
        @Valid ShippingAddressRequest shippingAddress
) {
}