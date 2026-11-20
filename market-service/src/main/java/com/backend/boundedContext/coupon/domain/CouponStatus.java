package com.backend.boundedContext.coupon.domain;

/**
 * 쿠폰 운영 상태 (DB에 저장하는 값)
 *
 * <p>누군가 멈췄는지만 저장한다. 시작 전(SCHEDULED)과 종료(ENDED)는 유효 기간과 현재 시각으로 계산하므로
 * 스케줄러가 상태를 바꿀 필요가 없다. 화면에 보여 줄 상태는 {@link Coupon#displayStatus}로 구한다.
 */
public enum CouponStatus {
    ACTIVE,    // 운영 중 (유효 기간에 따라 시작 전, 사용 가능, 종료로 보인다)
    STOPPED    // 중지됨 (판매자, 관리자, 시스템)
}
