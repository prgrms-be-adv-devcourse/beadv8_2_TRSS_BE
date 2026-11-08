package com.backend.boundedContext.delivery.domain;

import com.backend.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 배송 컨텍스트 오류 코드
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum DeliveryErrorCode implements ErrorCode {

    DELIVERY_NOT_FOUND(404, "배송 건을 찾을 수 없습니다."),
    DELIVERY_STATE_INVALID(409, "현재 배송 상태에서는 처리할 수 없습니다."),
    DELIVERY_TRACKING_DUPLICATED(409, "이미 다른 배송 건에 등록된 송장번호입니다."),
    DELIVERY_TRACKING_NUMBER_INVALID(400, "송장번호 형식이 올바르지 않습니다.");

    private final int status;
    private final String msg;
}
