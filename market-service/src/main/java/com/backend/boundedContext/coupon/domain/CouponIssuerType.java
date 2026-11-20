package com.backend.boundedContext.coupon.domain;

/**
 * 쿠폰을 등록한 주체. issuerId는 SELLER면 판매자 ID(seller.id), ADMIN이면 관리자 회원 ID
 */
public enum CouponIssuerType {
    SELLER,
    ADMIN
}
