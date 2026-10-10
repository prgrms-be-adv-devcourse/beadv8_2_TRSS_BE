package com.backend.boundedContext.member.domain;

import com.backend.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum MemberErrorCode implements ErrorCode {

    // 회원 조회
    MEMBER_NOT_FOUND(404, "회원을 찾을 수 없습니다."),

    // 회원가입·이메일 인증
    EMAIL_DUPLICATED(409, "이미 가입된 이메일입니다."),
    CODE_INVALID(400, "인증코드가 올바르지 않습니다."),
    CODE_EXPIRED(400, "인증코드가 만료되었습니다. 인증코드를 다시 요청해 주세요."),
    CODE_ATTEMPTS_EXCEEDED(400, "인증 시도 횟수를 초과했습니다. 인증코드를 다시 요청해 주세요."),
    EMAIL_NOT_VERIFIED(400, "이메일 인증이 완료되지 않았습니다.");

    private final int status;
    private final String msg;
}
