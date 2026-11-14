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
    COUPON_ORDER_LIMIT_EXCEEDED(409, "한 주문에는 상품 쿠폰과 장바구니 쿠폰을 종류별로 1개씩만 쓸 수 있습니다."),
    COUPON_NOT_APPLICABLE(400, "이 주문에 적용할 수 없는 쿠폰입니다."),
    COUPON_MIN_ORDER_NOT_MET(400, "쿠폰을 쓸 수 있는 최소 주문 금액에 미치지 않습니다."),

    // 상품 쿠폰 (판매자 발행, 코드 검증)
    COUPON_CODE_NOT_FOUND(404, "쿠폰 코드를 찾을 수 없습니다."),
    COUPON_PERIOD_OVERLAPPED(409, "같은 인플루언서와 상품으로 유효 기간이 겹치는 쿠폰이 있습니다."),
    COUPON_NOT_EDITABLE(409, "시작 전 쿠폰만 수정할 수 있고, 시작 후에는 종료 일시 연장만 할 수 있습니다."),
    PRODUCT_NOT_OWNED(400, "본인이 판매하는 상품이 아닙니다."),
    PRODUCT_NOT_PICKED(400, "협약한 인플루언서가 pick한 상품이 아닙니다."),
    PRODUCT_NOT_ON_SALE(409, "판매 중인 상품이 아닙니다."),

    // 협약
    PARTNERSHIP_NOT_FOUND(404, "협약을 찾을 수 없습니다."),
    PARTNERSHIP_ALREADY_EXISTS(409, "이 인플루언서와 요청 중이거나 진행 중인 협약이 있습니다."),
    PARTNERSHIP_STATE_INVALID(409, "현재 협약 상태에서는 처리할 수 없습니다."),
    PARTNERSHIP_NOT_ACTIVE(409, "진행 중인 협약이 아닙니다."),

    // 인플루언서 (member-service의 인플루언서 정보로 판단)
    INFLUENCER_NOT_FOUND(404, "인플루언서를 찾을 수 없습니다."),
    INFLUENCER_STATE_INVALID(409, "승인된 인플루언서에게만 협약을 요청할 수 있습니다."),
    INFLUENCER_NOT_APPROVED(403, "승인된 인플루언서만 이용할 수 있습니다.");

    private final int status;
    private final String msg;
}
