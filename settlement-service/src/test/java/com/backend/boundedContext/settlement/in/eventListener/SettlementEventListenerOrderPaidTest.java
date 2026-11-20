package com.backend.boundedContext.settlement.in.eventListener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventTypes;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderPaidEvent;

import tools.jackson.databind.json.JsonMapper;

/**
 * 배송 분리(TRSS-182) 뒤 order.events에 OrderPaidEvent가 함께 실려도 정산 컨슈머가 무시하는지 확인함.
 * 정산 코드는 바꾸지 않는다는 계약(palette_shared_contract 6장)의 근거. 봉투 읽기는 실제 EventMessages를 쓴다.
 */
class SettlementEventListenerOrderPaidTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final EventMessages eventMessages = new EventMessages(jsonMapper, new OutboxEventTypes());
    private final SettlementFacade settlementFacade = mock(SettlementFacade.class);
    private final ProcessedEvents processedEvents = mock(ProcessedEvents.class);
    private final SettlementEventListener listener =
            new SettlementEventListener(settlementFacade, eventMessages, processedEvents);

    @Test
    @DisplayName("eventType 헤더가 OrderPaidEvent면 payload를 읽지 않고 무시한다(처리 기록도 남기지 않음)")
    void orderPaidHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", "not-json");
        record.headers().add("eventType", "OrderPaidEvent".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> listener.handle(record)).doesNotThrowAnyException();

        verifyNoInteractions(processedEvents, settlementFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 OrderPaidEvent(콘솔 재전송 등)도 봉투를 읽어 거르고, 모르는 타입 예외(DLT)가 나지 않는다")
    void orderPaidWithoutHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", kafkaValue(paidEvent()));

        assertThatCode(() -> listener.handle(record)).doesNotThrowAnyException();

        verifyNoInteractions(processedEvents, settlementFacade);
    }

    @Test
    @DisplayName("헤더 없이 온, 이 앱이 아직 모르는 새 주문 이벤트도 역직렬화 실패(DLT) 없이 건너뛴다")
    void unknownEventWithoutHeader_ignored() {
        String value = """
                {"eventId":"%s","eventType":"OrderSomethingNewEvent","occurredAt":"2026-11-14T10:00:00",
                 "aggregateType":"ORDER","aggregateId":10,"payload":{"anything":1}}
                """.formatted(java.util.UUID.randomUUID());
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", value);

        assertThatCode(() -> listener.handle(record)).doesNotThrowAnyException();

        verifyNoInteractions(processedEvents, settlementFacade);
    }

    private static OrderPaidEvent paidEvent() {
        return new OrderPaidEvent(10L, "ORD-1", 3L, LocalDateTime.of(2026, 11, 6, 14, 30),
                new ShippingAddressDto("홍길동", "010-1234-5678", "06236", "서울", "101동", null),
                List.of(new PaidOrderItemDto(100L, 20L, 200L, 5L, "팔레트상점", "머그컵", "화이트", 2)));
    }

    // 릴레이가 Kafka로 보내는 값(봉투 JSON)
    private String kafkaValue(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        return eventMessages.toJson(OutboxEvent.create(envelope, jsonMapper.writeValueAsString(event)));
    }
}
