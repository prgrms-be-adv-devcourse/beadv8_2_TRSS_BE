package com.backend.boundedContext.order.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 주문서에서 고른 상품 쿠폰 (어느 장바구니 항목에 어떤 코드를 붙일지)
 *
 * @param cartItemId 할인을 붙일 장바구니 항목 (주문할 항목 중 하나)
 * @param code       상품 쿠폰 코드 (대소문자 구분 없음)
 */
public record ProductCouponSelectionRequest(
        @NotNull @Positive Long cartItemId,
        @NotBlank @Size(max = 20) String code
) {
}
