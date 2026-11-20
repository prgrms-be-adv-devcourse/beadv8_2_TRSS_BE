package com.backend.boundedContext.coupon.app.dto;

/**
 * 판매자 상품 쿠폰의 한 달 사용 집계 (판매자 쿠폰 화면의 이번 달 사용, 할인 총액)
 *
 * @param month              집계한 달 (yyyy-MM, 한국 시간 기준)
 * @param usedCount          그 달에 사용(USED)된 횟수
 * @param usedDiscountAmount 그 달에 사용된 할인 합계(원). 할인은 판매자 부담이고 정산은 할인 후 금액 기준
 */
public record SellerCouponMonthlyStatsDto(String month, long usedCount, long usedDiscountAmount) {
}
