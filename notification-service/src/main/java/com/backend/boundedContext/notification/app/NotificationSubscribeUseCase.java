package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.NotificationPolicy;
import com.backend.boundedContext.notification.out.NotificationEmitters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class NotificationSubscribeUseCase {

    private final NotificationEmitters notificationEmitters;
    private final NotificationGetUnreadCountUseCase notificationGetUnreadCountUseCase;
    private final NotificationPolicy notificationPolicy;

    // 연결을 등록하고 지금 안 읽은 개수를 바로 보냄
    public SseEmitter subscribe(Long memberId) {
        SseEmitter emitter = new SseEmitter(notificationPolicy.sseTimeoutMillis());
        notificationEmitters.add(memberId, emitter);
        notificationEmitters.send(memberId, NotificationEmitters.UNREAD_COUNT, notificationGetUnreadCountUseCase.getUnreadCount(memberId));
        return emitter;
    }
}
