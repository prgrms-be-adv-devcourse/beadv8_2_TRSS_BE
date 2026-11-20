package com.backend.boundedContext.notification.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 알림 컨텍스트 오류 코드
 * resultCode는 "{status}-{상수 이름}"으로 자동 생성
 * (예: 404-NOTIFICATION_NOT_FOUND)
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum NotificationErrorCode implements ErrorCode {

    // 다른 회원의 알림도 있는지 드러내지 않도록 같은 404로 응답함
    NOTIFICATION_NOT_FOUND(404, "알림을 찾을 수 없습니다.");

    private final int status;
    private final String msg;
}
