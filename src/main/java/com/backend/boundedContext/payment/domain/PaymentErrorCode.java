package com.backend.boundedContext.payment.domain;

import com.backend.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum PaymentErrorCode implements ErrorCode {

    // 지갑
    WALLET_NOT_FOUND(404, "지갑을 찾을 수 없습니다."),

    // 충전
    TOPUP_NOT_FOUND(404, "충전 내역을 찾을 수 없습니다."),
    TOPUP_AMOUNT_INVALID(400, "충전 금액은 10,000원 단위로 10,000원 이상 1,000,000원 이하만 가능합니다."),
    AMOUNT_MISMATCH(400, "충전 요청 금액이 일치하지 않습니다."),
    TOPUP_PAYMENT_DECLINED(400, "결제가 승인되지 않았습니다."),
    TOPUP_ALREADY_PROCESSED(409, "이미 처리된 충전입니다."),
    TOPUP_ALREADY_USED(409, "사용한 금액이 있는 충전 건은 취소할 수 없습니다.");

    private final int status;
    private final String msg;
}
