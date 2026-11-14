package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderReturnedEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * (QA, TRSS-226) 결제 담당이 받을 Kafka 메시지 모양을 고정한다.
 * 필드 이름·타입이 바뀌면 payment 컨슈머(PR-4)가 깨지므로 계약 문서와 같은 이름인지 본다.
 */
class OrderReturnedEventContractTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final EventMessages eventMessages = new EventMessages(jsonMapper, new OutboxEventTypes());

    @Test
    @DisplayName("봉투 JSON: eventType·aggregateType·aggregateId와 payload 필드 4개(orderId, buyerId, refundAmount 숫자, returnedAt ISO 문자열)")
    void wireFormat() {
        OrderReturnedEvent event = new OrderReturnedEvent(10L, 3L, 53_000L,
                LocalDateTime.of(2026, 11, 12, 16, 45, 30, 321_000_000));

        JsonNode json = jsonMapper.readTree(kafkaValue(event));

        assertThat(json.get("eventType").asString()).isEqualTo("OrderReturnedEvent");
        assertThat(json.get("aggregateType").asString()).isEqualTo("ORDER");
        assertThat(json.get("aggregateId").asLong()).isEqualTo(10L);
        JsonNode payload = json.get("payload");
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("orderId", "buyerId", "refundAmount", "returnedAt");
        assertThat(payload.get("refundAmount").isIntegralNumber()).isTrue();
        assertThat(payload.get("refundAmount").asLong()).isEqualTo(53_000L);
        assertThat(payload.get("returnedAt").isString()).isTrue();
        assertThat(LocalDateTime.parse(payload.get("returnedAt").asString())).isEqualTo(event.returnedAt());
    }

    @Test
    @DisplayName("int 범위를 넘는 환불 금액도 long 그대로 왕복한다")
    void largeRefundAmount() {
        OrderReturnedEvent event = new OrderReturnedEvent(10L, 3L, 3_000_000_000L, LocalDateTime.of(2026, 11, 12, 0, 0));

        EventEnvelope<DomainEvent> read = eventMessages.read(kafkaValue(event));

        assertThat(read.payload()).isEqualTo(event);
    }

    private String kafkaValue(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        return eventMessages.toJson(OutboxEvent.create(envelope, jsonMapper.writeValueAsString(event)));
    }
}
