package com.backend.boundedContext.coupon.domain;

/**
 * 쿠폰 종류
 */
public enum CouponType {
    PRODUCT,   // 상품 쿠폰. 판매자가 협약한 인플루언서의 pick 상품 1개에 발행 (판매자 부담)
    CART       // 장바구니 쿠폰. 관리자가 금액 구간별 정액 할인으로 등록 (플랫폼 부담)
}
