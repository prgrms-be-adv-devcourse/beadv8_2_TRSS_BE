package com.backend.boundedContext.coupon.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 쿠폰 컨텍스트 오류 코드
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum CouponErrorCode implements ErrorCode {

    // 등록과 관리
    COUPON_NOT_FOUND(404, "쿠폰을 찾을 수 없습니다."),
    COUPON_PERIOD_INVALID(400, "유효 기간은 시작이 종료보다 빨라야 하고, 종료는 현재 이후여야 합니다."),
    COUPON_TIER_INVALID(400, "할인 구간이 올바르지 않습니다. 최소 주문 금액과 할인액은 구간이 올라갈수록 커져야 하고, 할인액은 최소 주문 금액보다 작아야 합니다."),
    COUPON_STATE_INVALID(409, "현재 쿠폰 상태에서는 처리할 수 없습니다."),

    // 사용 (검증과 예약)
    COUPON_NOT_ACTIVE(409, "사용 기간이 아니거나 중지된 쿠폰입니다."),
    COUPON_SOLD_OUT(409, "쿠폰이 모두 소진되었습니다."),
    COUPON_ALREADY_USED(409, "이미 사용했거나 다른 주문에 예약된 쿠폰입니다."),
    COUPON_NOT_APPLICABLE(400, "이 주문에 적용할 수 없는 쿠폰입니다."),
    COUPON_MIN_ORDER_NOT_MET(400, "쿠폰을 쓸 수 있는 최소 주문 금액에 미치지 않습니다.");

    private final int status;
    private final String msg;
}
