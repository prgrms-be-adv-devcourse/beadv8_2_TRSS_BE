package com.backend.global.eventPublisher;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 도메인 이벤트 발행기.
 * 지금은 스프링 이벤트로 발행하고, Outbox·Kafka로 바꿀 때 이 클래스와 폴러만 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class EventPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;

    public void publish(Object event) {
        applicationEventPublisher.publishEvent(event);
    }
}
