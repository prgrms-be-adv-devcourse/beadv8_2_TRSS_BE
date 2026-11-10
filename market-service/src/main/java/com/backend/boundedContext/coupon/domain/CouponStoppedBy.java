package com.backend.boundedContext.coupon.domain;

/**
 * 쿠폰을 중지한 주체
 */
public enum CouponStoppedBy {
    SELLER,   // 판매자가 직접 중지
    ADMIN,    // 관리자 강제 중지 (사유 필수)
    SYSTEM    // 협약 종료, 상품 판매 중지와 삭제, 판매자 철회와 제재로 자동 중지
}
