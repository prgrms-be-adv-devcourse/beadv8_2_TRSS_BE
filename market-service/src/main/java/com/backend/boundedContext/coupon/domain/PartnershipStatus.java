package com.backend.boundedContext.coupon.domain;

/**
 * 판매자와 인플루언서의 협약 상태
 */
public enum PartnershipStatus {
    REQUESTED,   // 판매자가 요청, 인플루언서 응답 전
    ACTIVE,      // 인플루언서가 수락. 이 상태에서만 상품 쿠폰을 발행할 수 있다
    REJECTED,    // 인플루언서가 거절
    ENDED        // 판매자나 인플루언서가 종료, 또는 판매자 제재로 자동 종료
}
