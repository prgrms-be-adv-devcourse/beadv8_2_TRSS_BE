package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.CartCouponTier;

/**
 * 장바구니 쿠폰 할인 구간
 *
 * @param minOrderAmount 최소 주문 금액 (이 금액 이상)
 * @param discountAmount 할인액
 */
public record CartCouponTierDto(
        long minOrderAmount,
        long discountAmount
) {
    public static CartCouponTierDto from(CartCouponTier tier) {
        return new CartCouponTierDto(tier.getMinOrderAmount(), tier.getDiscountAmount());
    }
}
