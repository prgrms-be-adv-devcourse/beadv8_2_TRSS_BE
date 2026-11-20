package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.app.dto.NotificationDto;
import com.backend.boundedContext.notification.out.NotificationEmitters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 연결된 회원 화면으로 알림을 보냄. 연결이 없으면 아무것도 하지 않음
@Service
@RequiredArgsConstructor
public class NotificationSendUseCase {

    private final NotificationEmitters notificationEmitters;
    private final NotificationGetUnreadCountUseCase notificationGetUnreadCountUseCase;

    // 새 알림과 바뀐 안 읽은 개수를 보냄. 연결이 없으면 개수 조회도 하지 않음
    public void sendCreated(Long memberId, NotificationDto notification) {
        if (notificationEmitters.count(memberId) == 0) return;

        notificationEmitters.send(memberId, NotificationEmitters.NOTIFICATION, notification);
        sendUnreadCount(memberId);
    }

    // 안 읽은 개수를 보냄. 연결이 없으면 조회하지 않음
    public void sendUnreadCount(Long memberId) {
        if (notificationEmitters.count(memberId) == 0) return;
        notificationEmitters.send(memberId, NotificationEmitters.UNREAD_COUNT, notificationGetUnreadCountUseCase.getUnreadCount(memberId));
    }

    // 모든 연결에 heartbeat를 보냄. 끊긴 연결도 이때 정리됨
    public void sendHeartbeat() {
        notificationEmitters.sendHeartbeat();
    }
}
