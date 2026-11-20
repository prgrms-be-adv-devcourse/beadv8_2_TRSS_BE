package com.backend.boundedContext.coupon.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 쿠폰 할인 구간 (구간마다 1행)
 */
@Entity
@Table(name = "cart_coupon_tier",
        uniqueConstraints = @UniqueConstraint(name = "uk_cart_coupon_tier_min_amount",
                columnNames = {"coupon_id", "min_order_amount"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartCouponTier extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Column(nullable = false)
    private long minOrderAmount;

    @Column(nullable = false)
    private long discountAmount;

    static CartCouponTier create(Coupon coupon, CartCouponTierSpec spec) {
        CartCouponTier tier = new CartCouponTier();
        tier.coupon = coupon;
        tier.minOrderAmount = spec.minOrderAmount();
        tier.discountAmount = spec.discountAmount();
        return tier;
    }
}
