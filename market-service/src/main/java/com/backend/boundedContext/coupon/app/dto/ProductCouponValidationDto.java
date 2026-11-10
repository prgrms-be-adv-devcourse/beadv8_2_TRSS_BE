package com.backend.boundedContext.coupon.app.dto;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 코드 검증 결과 (주문서에서 코드 입력 시)
 *
 * @param cartItemId             할인이 붙을 장바구니 항목
 * @param lineAmount             그 항목의 현재 금액 (단가 × 수량)
 * @param expectedDiscountAmount 예상 할인액. 항목 금액을 넘지 않는다. 실제 할인은 주문 생성 때 다시 계산한다
 */
public record ProductCouponValidationDto(
        Long couponId,
        String code,
        String couponName,
        Long cartItemId,
        Long productId,
        long lineAmount,
        long expectedDiscountAmount,
        LocalDateTime validUntil
) {
}
