package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// 열린 SSE 연결이 조용해도 끊기지 않게 주기적으로 heartbeat를 보냄
@Component
@RequiredArgsConstructor
public class NotificationHeartbeatScheduler {

    private final NotificationFacade notificationFacade;

    @Scheduled(fixedDelayString = "${custom.notification.heartbeatIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void sendHeartbeat() {
        notificationFacade.sendHeartbeat();
    }
}
