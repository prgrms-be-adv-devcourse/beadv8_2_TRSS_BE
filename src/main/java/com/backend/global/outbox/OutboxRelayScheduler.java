package com.backend.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Outbox 릴레이 스케줄러. @EnableScheduling(공통 SchedulingConfig)이 있어야 실행됨
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private final OutboxRelay outboxRelay;

    // 이전 실행이 끝난 뒤 relayIntervalMillis 밀리초마다 실행
    @Scheduled(fixedDelayString = "${custom.outbox.relayIntervalMillis}", timeUnit = TimeUnit.MILLISECONDS)
    public void relay() {
        try {
            outboxRelay.relayPending();
        } catch (Exception e) {
            log.error("Outbox 릴레이 실행에 실패했습니다. 다음 실행에서 다시 처리합니다.", e);
        }
    }
}
