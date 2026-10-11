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
    WALLET_NOT_FOUND(404, "지갑을 찾을 수 없습니다.");

    private final int status;
    private final String msg;
}
