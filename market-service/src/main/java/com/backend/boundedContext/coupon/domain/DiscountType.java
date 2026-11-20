package com.backend.boundedContext.coupon.domain;

/**
 * 상품 쿠폰 할인 방식
 */
public enum DiscountType {
    FIXED,   // 정액 (원)
    RATE     // 정률 (%). 최대 할인액 필수, 원 단위 버림
}
