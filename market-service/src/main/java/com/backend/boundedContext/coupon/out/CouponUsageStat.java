package com.backend.boundedContext.coupon.out;

/**
 * 쿠폰별 사용 집계 ({@link CouponUsageRepository#sumByCouponIdsAndStatus})
 *
 * @param couponId       쿠폰 ID
 * @param count          사용 수
 * @param discountAmount 할인 합계 (원)
 */
public record CouponUsageStat(
        Long couponId,
        long count,
        long discountAmount
) {
    public static CouponUsageStat empty(Long couponId) {
        return new CouponUsageStat(couponId, 0, 0);
    }
}
