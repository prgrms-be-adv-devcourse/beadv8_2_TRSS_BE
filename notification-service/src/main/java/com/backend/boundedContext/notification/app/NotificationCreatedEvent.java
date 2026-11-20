package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationDto;

// 알림을 저장한 트랜잭션 안에서 발행함. 커밋된 뒤 SSE로 보내기 위한 서비스 내부 이벤트
public record NotificationCreatedEvent(
        Long memberId,
        NotificationDto notification
) {}
