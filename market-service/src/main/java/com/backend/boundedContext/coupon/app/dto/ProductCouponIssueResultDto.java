package com.backend.boundedContext.coupon.app.dto;

/**
 * 상품 쿠폰 발행 결과
 *
 * @param code 쿠폰 코드 (인플루언서가 구독자에게 전달)
 */
public record ProductCouponIssueResultDto(
        Long couponId,
        String code
) {
}
