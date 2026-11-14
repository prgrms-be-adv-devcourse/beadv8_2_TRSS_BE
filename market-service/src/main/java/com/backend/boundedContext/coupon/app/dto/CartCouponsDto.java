package com.backend.boundedContext.coupon.app.dto;

import java.util.List;

/**
 * 사용 가능한 장바구니 쿠폰 목록
 *
 * @param orderAmount cartItemIds를 보냈으면 그 항목들의 현재 상품 금액 합계(배송비 제외), 안 보냈으면 null
 * @param coupons     지금 사용 기간 안에 있고 중지되지 않은 장바구니 쿠폰 (종료가 가까운 순)
 */
public record CartCouponsDto(
        Long orderAmount,
        List<CartCouponDto> coupons
) {
}
