package com.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum GlobalErrorCode implements ErrorCode {
    // 요청 형식
    VALIDATION_FAILED(400, "입력값이 올바르지 않습니다."),
    INVALID_REQUEST(400, "요청 형식이 올바르지 않습니다."),
    HEADER_REQUIRED(400, "필수 요청 헤더가 없습니다."),

    // 인증·권한
    UNAUTHORIZED(401, "로그인이 필요합니다."),
    AUTH_TOKEN_EXPIRED(401, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    FORBIDDEN(403, "접근 권한이 없습니다."),

    // 경로
    RESOURCE_NOT_FOUND(404, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 방식입니다."),

    // 동시성·DB
    CONCURRENT_UPDATE(409, "다른 요청이 먼저 처리되었습니다. 다시 시도해 주세요."),
    DATA_CONFLICT(409, "이미 처리되었거나 충돌하는 요청입니다."),
    IDEMPOTENCY_KEY_REUSED(409, "같은 요청 키로 다른 요청을 보낼 수 없습니다."),

    // 요청 제한
    TOO_MANY_REQUESTS(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    // 외부·서버
    EXTERNAL_SERVICE_ERROR(502, "외부 서비스 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(500, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String msg;
}
