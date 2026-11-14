package com.backend.boundedContext.coupon.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 상품 쿠폰 코드 검증 요청 (주문서)
 *
 * @param code       쿠폰 코드 (대소문자 구분 없음, 앞뒤 공백 무시)
 * @param cartItemId 할인을 붙일 장바구니 항목. 상품 쿠폰은 한 주문에 1개, 항목 1개에 붙으므로 구매자가 정한다
 */
public record ProductCouponValidateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotNull Long cartItemId
) {
}
