package com.backend.boundedContext.coupon.domain;

/**
 * 상품 쿠폰 할인 내용 입력값
 *
 * @param discountValue     FIXED면 할인액(원), RATE면 할인율(1~100)
 * @param maxDiscountAmount RATE일 때 최대 할인액(원, 필수). FIXED면 null
 */
public record ProductCouponDiscount(
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount
) {
}
