package com.backend.shared.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventTypes;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderPaidEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배송 공유 계약(QA 보강): 서비스 사이를 오가는 JSON 모양과 null 값 처리.
 * 보내는 쪽(market·delivery)과 받는 쪽이 다른 앱이라, 필드 이름·날짜 형식이 바뀌면 계약이 깨진다.
 */
class DeliveryContractJsonTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final EventMessages eventMessages = new EventMessages(jsonMapper, new OutboxEventTypes());

    @Test
    @DisplayName("OrderPaidEvent payload는 필드 이름 그대로, 날짜는 ISO 문자열로 나간다")
    void orderPaidEvent_jsonShape() {
        OrderPaidEvent event = new OrderPaidEvent(10L, "ORD-1", 3L, LocalDateTime.of(2026, 11, 6, 14, 30, 15),
                new ShippingAddressDto("홍길동", "010-1234-5678", "06236", "서울", null, null),
                List.of(new PaidOrderItemDto(100L, 20L, null, 5L, "팔레트상점", "머그컵", null, 2)));

        JsonNode payload = payloadOf(event);

        assertThat(payload.get("paidAt").isString()).isTrue();
        assertThat(payload.get("paidAt").asString()).isEqualTo("2026-11-06T14:30:15");
        assertThat(payload.get("shippingAddress").get("receiverName").asString()).isEqualTo("홍길동");
        assertThat(payload.get("items").isArray()).isTrue();
        JsonNode item = payload.get("items").get(0);
        assertThat(item.get("orderItemId").asLong()).isEqualTo(100L);
        assertThat(item.get("sellerName").asString()).isEqualTo("팔레트상점");
        assertThat(item.get("quantity").asInt()).isEqualTo(2);
        assertThat(item.get("optionId").isNull()).isTrue();
        // aggregateType/aggregateId는 record 컴포넌트가 아니지만 메서드라 JSON에 실려도 읽을 때 문제가 없어야 한다
        assertThat(roundTrip(event).payload()).isEqualTo(event);
    }

    @Test
    @DisplayName("배송지·주소 상세·요청사항이 null이어도, 항목 목록이 비어 있어도 왕복하면 그대로다")
    void orderPaidEvent_nullFields_roundTrip() {
        OrderPaidEvent noAddress = new OrderPaidEvent(11L, "ORD-2", 3L,
                LocalDateTime.of(2026, 11, 6, 0, 0), null, List.of());
        OrderPaidEvent nullItems = new OrderPaidEvent(12L, "ORD-3", 3L, null,
                new ShippingAddressDto("홍길동", "010", "06236", "서울", null, null), null);

        assertThat(roundTrip(noAddress).payload()).isEqualTo(noAddress);
        assertThat(roundTrip(nullItems).payload()).isEqualTo(nullItems);
    }

    @Test
    @DisplayName("READY 배송 정보(택배사·송장·시각 null)와 DELIVERED 배송 정보가 JSON 왕복 후 그대로다, 상태는 이름 문자열")
    void deliveryDto_roundTrip() {
        DeliveryDto ready = new DeliveryDto(1L, DeliveryStatus.READY, null, null, null, null);
        DeliveryDto delivered = new DeliveryDto(2L, DeliveryStatus.DELIVERED, "CJ대한통운", "123456789012",
                LocalDateTime.of(2026, 11, 6, 9, 0, 0, 1), LocalDateTime.of(2026, 11, 8, 18, 30));

        String readyJson = jsonMapper.writeValueAsString(ready);

        assertThat(jsonMapper.readTree(readyJson).get("status").asString()).isEqualTo("READY");
        assertThat(jsonMapper.readValue(readyJson, DeliveryDto.class)).isEqualTo(ready);
        assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(delivered), DeliveryDto.class))
                .isEqualTo(delivered);
    }

    @Test
    @DisplayName("배송 완료 이벤트 envelope의 aggregateType은 DELIVERY, aggregateId는 배송 ID(주문 ID 아님)")
    void deliveryCompletedEvent_aggregate() {
        var event = new com.backend.shared.delivery.event.DeliveryCompletedEvent(30L, 10L, LocalDateTime.of(2026, 11, 8, 9, 5));

        EventEnvelope<DomainEvent> read = roundTrip(event);

        assertThat(read.aggregateType()).isEqualTo("DELIVERY");
        assertThat(read.aggregateId()).isEqualTo(30L);
        assertThat(EventMessages.topicOf(read.aggregateType())).isEqualTo("delivery.events");
    }

    private JsonNode payloadOf(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        OutboxEvent outbox = OutboxEvent.create(envelope, jsonMapper.writeValueAsString(event));
        return jsonMapper.readTree(eventMessages.toJson(outbox)).get("payload");
    }

    private EventEnvelope<DomainEvent> roundTrip(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        OutboxEvent outbox = OutboxEvent.create(envelope, jsonMapper.writeValueAsString(event));
        return eventMessages.read(eventMessages.toJson(outbox));
    }
}
