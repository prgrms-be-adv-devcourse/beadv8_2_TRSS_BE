package com.backend.boundedContext.coupon.domain;

/**
 * 화면과 API에 보여 주는 쿠폰 상태. 저장하지 않고 운영 상태와 유효 기간으로 계산한다.
 *
 * <p>계산 순서: STOPPED → (현재 &lt; 시작) SCHEDULED → (현재 &gt;= 종료) ENDED → ACTIVE.
 * 시작 시각은 포함하고 종료 시각은 포함하지 않는다(validFrom &lt;= 현재 &lt; validUntil).
 */
public enum CouponDisplayStatus {
    SCHEDULED,   // 시작 전
    ACTIVE,      // 사용 가능
    ENDED,       // 유효 기간 종료
    STOPPED      // 중지됨. 중지 후 기간이 지나도 STOPPED로 보인다(중지 사유가 더 중요한 정보라서)
}
