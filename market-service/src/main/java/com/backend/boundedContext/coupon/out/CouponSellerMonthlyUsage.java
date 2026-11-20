package com.backend.boundedContext.coupon.out;

/**
 * 판매자가 발행한 상품 쿠폰의 기간 사용 합계 (USED만, 예약 중이거나 해제된 것은 세지 않는다)
 *
 * @param usedCount          사용 횟수
 * @param usedDiscountAmount 할인 합계(원)
 */
public record CouponSellerMonthlyUsage(Long usedCount, Long usedDiscountAmount) {
}
