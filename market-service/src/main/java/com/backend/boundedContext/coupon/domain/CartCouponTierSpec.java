package com.backend.boundedContext.coupon.domain;

/**
 * 장바구니 쿠폰 할인 구간 입력값
 *
 * @param minOrderAmount 최소 주문 금액 (이 금액 이상이면 구간에 들어감)
 * @param discountAmount 할인액 (정액)
 */
public record CartCouponTierSpec(
        long minOrderAmount,
        long discountAmount
) {
}
