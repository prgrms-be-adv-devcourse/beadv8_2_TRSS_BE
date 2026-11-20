package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.Coupon;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 구매자에게 보여 주는 사용 가능한 장바구니 쿠폰
 *
 * @param tiers                  할인 구간 (최소 주문 금액 오름차순)
 * @param used                   이미 사용했거나 다른 주문에 예약했는지 (1인 1회)
 * @param soldOut                수량 제한 쿠폰이 모두 소진됐는지
 * @param expectedDiscountAmount 조회할 때 cartItemIds를 보냈으면 그 항목들 금액으로 계산한 할인액, 안 보냈으면 null.
 *                               이미 썼거나 소진됐거나 구간에 못 미치면 0
 * @param applicable             cartItemIds를 보냈으면 지금 적용 가능한지(할인액 &gt; 0), 안 보냈으면 null
 */
public record CartCouponDto(
        Long couponId,
        String name,
        List<CartCouponTierDto> tiers,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        boolean used,
        boolean soldOut,
        Long expectedDiscountAmount,
        Boolean applicable
) {
    public static CartCouponDto of(Coupon coupon, boolean used, Long orderAmount) {
        boolean soldOut = coupon.isSoldOut();
        Long expectedDiscount = null;
        Boolean applicable = null;

        if (orderAmount != null) {
            expectedDiscount = (used || soldOut) ? 0L : coupon.cartDiscountFor(orderAmount);
            applicable = expectedDiscount > 0;
        }

        return new CartCouponDto(
                coupon.getId(),
                coupon.getName(),
                coupon.getTiers().stream().map(CartCouponTierDto::from).toList(),
                coupon.getValidFrom(),
                coupon.getValidUntil(),
                used,
                soldOut,
                expectedDiscount,
                applicable
        );
    }
}
