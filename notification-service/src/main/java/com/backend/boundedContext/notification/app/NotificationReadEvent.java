package com.backend.boundedContext.notification.app;

// 읽음 처리한 트랜잭션 안에서 발행함. 커밋된 뒤 바뀐 안 읽은 개수를 SSE로 보내기 위한 서비스 내부 이벤트
public record NotificationReadEvent(
        Long memberId
) {}
