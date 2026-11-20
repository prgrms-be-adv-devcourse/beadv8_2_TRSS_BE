package com.backend.boundedContext.notification.in;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventTypes;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderReturnedEvent;

import tools.jackson.databind.json.JsonMapper;

/**
 * (QA, TRSS-226) order.events에 반품 승인 이벤트(OrderReturnedEvent)가 실려도 이 컨슈머가 무시하는지 확인한다.
 * "구독자 코드 변경 없음"이라는 공유 계약의 근거. 봉투 읽기는 실제 EventMessages·OutboxEventTypes(클래스패스 스캔)를 쓴다.
 */
class NotificationOrderEventListenerOrderReturnedTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final EventMessages eventMessages = new EventMessages(jsonMapper, new OutboxEventTypes());
    private final NotificationFacade facade = mock(NotificationFacade.class);
    private final ProcessedEvents processedEvents = mock(ProcessedEvents.class);
    private final NotificationOrderEventListener listener = new NotificationOrderEventListener(facade, eventMessages, processedEvents);

    @Test
    @DisplayName("eventType 헤더가 OrderReturnedEvent면 payload를 읽지 않고 무시한다(처리 기록도 남기지 않음)")
    void returnedHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", "not-json");
        record.headers().add("eventType", "OrderReturnedEvent".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> listener.handle(record)).doesNotThrowAnyException();

        verifyNoInteractions(processedEvents, facade);
    }

    @Test
    @DisplayName("릴레이가 보낸 그대로(헤더 + 봉투 JSON)의 OrderReturnedEvent도 무시한다")
    void returnedWithHeaderAndRealValue_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", kafkaValue(returnedEvent()));
        record.headers().add("eventType", "OrderReturnedEvent".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> listener.handle(record)).doesNotThrowAnyException();

        verifyNoInteractions(processedEvents, facade);
    }

    @Test
    @DisplayName("헤더 없이 온 OrderReturnedEvent(콘솔 재전송 등)도 봉투를 읽어 거르고, 모르는 타입 예외(DLT)가 나지 않는다")
    void returnedWithoutHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", kafkaValue(returnedEvent()));

        assertThatCode(() -> listener.handle(record)).doesNotThrowAnyException();

        verifyNoInteractions(processedEvents, facade);
    }

    private static OrderReturnedEvent returnedEvent() {
        return new OrderReturnedEvent(10L, 3L, 53_000L, LocalDateTime.of(2026, 11, 12, 16, 45, 30));
    }

    // 릴레이가 Kafka로 보내는 값(봉투 JSON)
    private String kafkaValue(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        return eventMessages.toJson(OutboxEvent.create(envelope, jsonMapper.writeValueAsString(event)));
    }
}
