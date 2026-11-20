package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.ProductCoupon;

/**
 * 관리자 쿠폰 목록, 상세에 붙는 상품 쿠폰 정보 (장바구니 쿠폰이면 null)
 *
 * @param maxDiscountAmount RATE일 때만 있음
 */
public record ProductCouponInfoDto(
        Long partnershipId,
        Long influencerId,
        Long productId,
        String code,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount
) {
    public static ProductCouponInfoDto from(ProductCoupon productCoupon) {
        if (productCoupon == null) return null;
        return new ProductCouponInfoDto(
                productCoupon.getPartnershipId(),
                productCoupon.getInfluencerId(),
                productCoupon.getProductId(),
                productCoupon.getCode(),
                productCoupon.getDiscountType(),
                productCoupon.getDiscountValue(),
                productCoupon.getMaxDiscountAmount()
        );
    }
}
