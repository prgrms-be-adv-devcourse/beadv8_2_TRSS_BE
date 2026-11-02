package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.event.OutboxTestEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EventMessagesTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final EventMessages eventMessages = new EventMessages(jsonMapper, new OutboxEventTypes());

    @Test
    @DisplayName("토픽 이름은 대상 종류 소문자 + .events")
    void topicOf() {
        assertThat(EventMessages.topicOf("ORDER")).isEqualTo("order.events");
        assertThat(EventMessages.topicOf("MEMBER")).isEqualTo("member.events");
    }

    @Test
    @DisplayName("outbox 행을 봉투 JSON으로 바꾸고, 다시 읽으면 같은 봉투가 된다")
    void roundTrip() {
        EventEnvelope<OutboxTestEvent> envelope = EventEnvelope.wrap(new OutboxTestEvent(7L));
        OutboxEvent outbox = OutboxEvent.create(envelope, "{\"testId\":7}");

        String json = eventMessages.toJson(outbox);
        EventEnvelope<DomainEvent> read = eventMessages.read(json);

        assertThat(read.eventId()).isEqualTo(envelope.eventId());
        assertThat(read.eventType()).isEqualTo("OutboxTestEvent");
        assertThat(read.occurredAt()).isEqualTo(envelope.occurredAt());
        assertThat(read.aggregateType()).isEqualTo("TEST");
        assertThat(read.aggregateId()).isEqualTo(7L);
        assertThat(read.payload()).isEqualTo(new OutboxTestEvent(7L));
    }

    @Test
    @DisplayName("payload는 문자열이 아니라 JSON 객체로 들어간다(받는 쪽이 바로 읽을 수 있게)")
    void payloadIsObject() {
        OutboxEvent outbox = OutboxEvent.create(EventEnvelope.wrap(new OutboxTestEvent(7L)), "{\"testId\":7}");

        JsonNode json = jsonMapper.readTree(eventMessages.toJson(outbox));

        assertThat(json.get("payload").isObject()).isTrue();
        assertThat(json.get("payload").get("testId").asLong()).isEqualTo(7L);
    }

    @Test
    @DisplayName("이 앱이 모르는 eventType이면 읽지 않고 예외")
    void unknownEventType() {
        String json = """
                {"eventId":"00000000-0000-0000-0000-000000000001","eventType":"NoSuchEvent",
                 "occurredAt":"2026-10-30T10:00:00","aggregateType":"TEST","aggregateId":1,"payload":{}}
                """;

        assertThatThrownBy(() -> eventMessages.read(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JSON은 맞지만 봉투 필드가 없으면 NullPointerException이 아니라 IllegalArgumentException(컨슈머가 재시도 없이 DLT로 보냄)")
    void missingField() {
        String noEventId = """
                {"eventType":"OutboxTestEvent","occurredAt":"2026-10-30T10:00:00","aggregateType":"TEST","aggregateId":1,
                 "payload":{"testId":1}}
                """;

        assertThatThrownBy(() -> eventMessages.read("{}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventType");
        assertThatThrownBy(() -> eventMessages.read(noEventId))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventId");
    }
}
