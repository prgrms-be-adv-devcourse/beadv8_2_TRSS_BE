package com.backend.global.outbox;

import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Kafka 메시지 ↔ 이벤트 봉투.
 * 릴레이는 outbox 행을 봉투 JSON으로 바꿔 보내고, 컨슈머는 받은 JSON을 다시 봉투로 읽는다.
 */
@Component
@RequiredArgsConstructor
public class EventMessages {

    private final ObjectMapper objectMapper;
    private final OutboxEventTypes outboxEventTypes;

    /** 이벤트가 생긴 대상 종류로 정한 토픽 이름 */
    public static String topicOf(String aggregateType) {
        return aggregateType.toLowerCase(Locale.ROOT) + ".events";
    }

    /** outbox 행을 Kafka 메시지 값(봉투 JSON)으로 바꾼다. payload는 저장된 JSON을 그대로 넣는다. */
    public String toJson(OutboxEvent outbox) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", outbox.getEventId().toString());
        envelope.put("eventType", outbox.getEventType());
        envelope.put("occurredAt", outbox.getOccurredAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        envelope.put("aggregateType", outbox.getAggregateType());
        envelope.put("aggregateId", outbox.getAggregateId());
        envelope.set("payload", objectMapper.readTree(outbox.getPayload()));
        return objectMapper.writeValueAsString(envelope);
    }

    /**
     * Kafka 메시지 값(봉투 JSON)을 봉투로 읽는다. payload는 eventType의 이벤트 record로 되돌린다.
     *
     * @throws IllegalArgumentException 이 앱이 모르는 eventType인 경우
     */
    public EventEnvelope<DomainEvent> read(String json) {
        JsonNode envelope = objectMapper.readTree(json);
        String eventType = envelope.get("eventType").asString();
        Class<? extends DomainEvent> type = outboxEventTypes.find(eventType)
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 이벤트 타입: " + eventType));

        return new EventEnvelope<>(
                UUID.fromString(envelope.get("eventId").asString()),
                eventType,
                LocalDateTime.parse(envelope.get("occurredAt").asString()),
                envelope.get("aggregateType").asString(),
                envelope.get("aggregateId").asLong(),
                objectMapper.treeToValue(envelope.get("payload"), type)
        );
    }
}
