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
     * @throws IllegalArgumentException 봉투에 필요한 필드가 없거나, 이 앱이 모르는 eventType인 경우
     */
    public EventEnvelope<DomainEvent> read(String json) {
        JsonNode envelope = objectMapper.readTree(json);
        String eventType = required(envelope, "eventType").asString();
        Class<? extends DomainEvent> type = outboxEventTypes.find(eventType)
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 이벤트 타입: " + eventType));

        return new EventEnvelope<>(
                UUID.fromString(required(envelope, "eventId").asString()),
                eventType,
                LocalDateTime.parse(required(envelope, "occurredAt").asString()),
                required(envelope, "aggregateType").asString(),
                required(envelope, "aggregateId").asLong(),
                objectMapper.treeToValue(required(envelope, "payload"), type)
        );
    }

    /**
     * 봉투의 eventType만 읽는다(payload를 이벤트 record로 바꾸지 않음).
     * eventType 헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)를 컨슈머가 다룰지 먼저 판단할 때 쓴다.
     * 이 앱이 모르는 새 이벤트 타입이어도 예외 없이 이름을 돌려주므로, 다루지 않는 이벤트를 DLT로 보내지 않고 건너뛸 수 있다.
     *
     * @throws IllegalArgumentException 봉투에 eventType이 없는 경우
     */
    public String eventTypeOf(String json) {
        return required(objectMapper.readTree(json), "eventType").asString();
    }

    // 필드가 없으면 NullPointerException 대신 메시지가 잘못됐다는 예외를 던진다(컨슈머는 재시도 없이 DLT로 보낸다)
    private static JsonNode required(JsonNode envelope, String field) {
        JsonNode value = envelope.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("이벤트 봉투에 " + field + "가 없습니다");
        }
        return value;
    }
}
