package com.backend.boundedContext.chat.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 챗봇 컨텍스트 오류 코드
 * resultCode는 "{status}-{상수 이름}"으로 자동 생성
 * (예: 404-CONVERSATION_NOT_FOUND)
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum ChatErrorCode implements ErrorCode {

    // 다른 회원의 대화도 같은 응답(있는지 알려 주지 않음)
    CONVERSATION_NOT_FOUND(404, "대화를 찾을 수 없습니다.");

    private final int status;
    private final String msg;
}
