package com.backend.boundedContext.faq.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * FAQ 컨텍스트 오류 코드
 * resultCode는 "{status}-{상수 이름}"으로 자동 생성
 * (예: 404-FAQ_NOT_FOUND)
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum FaqErrorCode implements ErrorCode {

    FAQ_NOT_FOUND(404, "FAQ를 찾을 수 없습니다.");

    private final int status;
    private final String msg;
}
