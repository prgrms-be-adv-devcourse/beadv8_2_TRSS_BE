package com.backend.shared.event;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 모든 이벤트에 공통으로 붙는 봉투.
 * outbox_event 한 행, Kafka 메시지 하나와 1:1로 대응하고, 받는 쪽은 {@code eventId}로 중복을 거른다.
 *
 * @param eventId       이벤트 고유 ID. 컨슈머 멱등 처리 기준
 * @param eventType     payload record의 클래스 단순 이름(예: {@code OrderConfirmedEvent})
 * @param occurredAt    발행 시각(KST, 마이크로초까지. DB timestamp(6)에 저장해도 값이 바뀌지 않게)
 * @param aggregateType 이벤트가 생긴 집합 종류
 * @param aggregateId   이벤트가 생긴 집합의 ID
 * @param payload       기존 이벤트 record 그대로
 */
public record EventEnvelope<T extends DomainEvent>(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        String aggregateType,
        Long aggregateId,
        T payload
) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
    }

    /** 새 eventId와 현재 시각으로 이벤트를 봉투에 담는다. */
    public static <T extends DomainEvent> EventEnvelope<T> wrap(T payload) {
        Objects.requireNonNull(payload, "payload");
        return new EventEnvelope<>(
                UUID.randomUUID(),
                payload.getClass().getSimpleName(),
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS),   // 리눅스는 나노초까지 나오고 DB는 마이크로초로 반올림해서 값이 달라짐
                payload.aggregateType(),
                payload.aggregateId(),
                payload
        );
    }
}
