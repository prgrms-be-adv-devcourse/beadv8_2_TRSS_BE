package com.backend.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Outbox 릴레이 스케줄러. @EnableScheduling(공통 SchedulingConfig)이 있어야 실행됨
 * custom.outbox.relayEnabled=false면 등록하지 않는다. 테스트는 이 값을 끄고(src/test/resources/application.properties),
 * 다른 테스트가 막 저장한 PENDING 이벤트를 릴레이가 몰래 발행하지 않게 한다. 필요하면 OutboxRelay를 직접 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.outbox.relayEnabled", havingValue = "true", matchIfMissing = true)
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
