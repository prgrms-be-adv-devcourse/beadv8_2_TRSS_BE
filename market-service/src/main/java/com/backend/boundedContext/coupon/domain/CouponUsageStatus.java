package com.backend.boundedContext.coupon.domain;

/**
 * 회원별 쿠폰 사용 기록 상태
 */
public enum CouponUsageStatus {
    RESERVED,   // 주문 생성 때 예약 (재고 차감과 같은 트랜잭션)
    USED,       // 결제 성공
    RELEASED    // 결제 전 취소나 만료로 예약 해제 (남은 수량도 되돌린다)
}
