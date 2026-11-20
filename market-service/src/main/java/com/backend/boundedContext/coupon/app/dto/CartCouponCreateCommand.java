package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.CartCouponTierSpec;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 장바구니 쿠폰 등록 입력값
 *
 * @param totalQuantity 수량 제한이 없으면 null
 */
public record CartCouponCreateCommand(
        String name,
        List<CartCouponTierSpec> tiers,
        boolean quantityLimited,
        Integer totalQuantity,
        LocalDateTime validFrom,
        LocalDateTime validUntil
) {
}
