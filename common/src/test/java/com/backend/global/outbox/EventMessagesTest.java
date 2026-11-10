package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.delivery.event.DeliveryShippedEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.event.OutboxTestEvent;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderPaidEvent;

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
        assertThat(EventMessages.topicOf("DELIVERY")).isEqualTo("delivery.events");
    }

    @Test
    @DisplayName("결제 완료 이벤트(배송지·항목 2개)는 order.events로 가고, JSON으로 보냈다 읽으면 값이 그대로다")
    void orderPaidEventRoundTrip() {
        OrderPaidEvent event = new OrderPaidEvent(10L, "ORD-20261106-0001", 3L,
                LocalDateTime.of(2026, 11, 6, 14, 30, 15, 123_000_000),
                new ShippingAddressDto("홍길동", "010-1234-5678", "06236",
                        "서울시 강남구 테헤란로 1", "101동 202호", "문 앞에 놓아 주세요"),
                List.of(new PaidOrderItemDto(100L, 20L, 200L, 5L, "팔레트상점", "머그컵", "화이트", 2),
                        new PaidOrderItemDto(101L, 21L, null, 6L, "다른상점", "접시", null, 1)));

        EventEnvelope<DomainEvent> read = roundTrip(event);

        assertThat(EventMessages.topicOf(read.aggregateType())).isEqualTo("order.events");
        assertThat(read.eventType()).isEqualTo("OrderPaidEvent");
        assertThat(read.aggregateId()).isEqualTo(10L);
        assertThat(read.payload()).isEqualTo(event);
    }

    @Test
    @DisplayName("배송 완료 이벤트는 delivery.events로 가고, JSON으로 보냈다 읽으면 값이 그대로다")
    void deliveryCompletedEventRoundTrip() {
        DeliveryCompletedEvent event = new DeliveryCompletedEvent(30L, 10L, 3L,
                LocalDateTime.of(2026, 11, 8, 9, 5, 0, 456_000_000));

        EventEnvelope<DomainEvent> read = roundTrip(event);

        assertThat(EventMessages.topicOf(read.aggregateType())).isEqualTo("delivery.events");
        assertThat(read.eventType()).isEqualTo("DeliveryCompletedEvent");
        assertThat(read.aggregateId()).isEqualTo(30L);
        assertThat(read.payload()).isEqualTo(event);
    }

    @Test
    @DisplayName("배송 시작 이벤트는 delivery.events로 가고, JSON으로 보냈다 읽으면 값이 그대로다")
    void deliveryShippedEventRoundTrip() {
        DeliveryShippedEvent event = new DeliveryShippedEvent(30L, 10L, 3L, "CJ대한통운", "123456789012",
                LocalDateTime.of(2026, 11, 7, 11, 20, 0, 789_000_000));

        EventEnvelope<DomainEvent> read = roundTrip(event);

        assertThat(EventMessages.topicOf(read.aggregateType())).isEqualTo("delivery.events");
        assertThat(read.eventType()).isEqualTo("DeliveryShippedEvent");
        assertThat(read.aggregateId()).isEqualTo(30L);
        assertThat(read.payload()).isEqualTo(event);
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

    // EventPublisher처럼 이벤트를 JSON으로 저장한 outbox 행을 만들고, 릴레이 → 컨슈머 순서로 보냈다 읽는다
    private EventEnvelope<DomainEvent> roundTrip(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        OutboxEvent outbox = OutboxEvent.create(envelope, jsonMapper.writeValueAsString(event));
        return eventMessages.read(eventMessages.toJson(outbox));
    }
}
