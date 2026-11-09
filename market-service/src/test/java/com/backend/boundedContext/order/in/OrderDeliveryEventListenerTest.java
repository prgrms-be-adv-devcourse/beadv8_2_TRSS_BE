package com.backend.boundedContext.order.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.delivery.event.DeliveryShippedEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderPaidEvent;

@ExtendWith(MockitoExtension.class)
class OrderDeliveryEventListenerTest {

    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 11, 3, 15, 0);

    @Mock private OrderFacade orderFacade;
    @Mock private EventMessages eventMessages;
    @Mock private ProcessedEvents processedEvents;

    @InjectMocks private OrderDeliveryEventListener listener;

    @Test
    @DisplayName("배송 완료 이벤트를 받으면 주문의 배송 완료 시각을 기록한다")
    void completed_marksDelivered() {
        receive(new DeliveryCompletedEvent(70L, 500L, 3L, DELIVERED_AT), true, true);

        verify(orderFacade).markDelivered(500L, DELIVERED_AT);
    }

    @Test
    @DisplayName("이미 처리한 이벤트면 주문을 건드리지 않는다")
    void alreadyProcessed_skips() {
        receive(new DeliveryCompletedEvent(70L, 500L, 3L, DELIVERED_AT), true, false);

        verify(orderFacade, never()).markDelivered(any(), any());
    }

    @Test
    @DisplayName("다른 이벤트 타입 헤더면 payload를 읽지 않고 무시한다 (모르는 새 배송 이벤트여도 DLT로 보내지 않음)")
    void otherEventTypeHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.events", 0, 0, "70", "{}");
        record.headers().add("eventType", "DeliveryShippedEvent".getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, orderFacade);
    }

    @Test
    @DisplayName("배송 시작 이벤트(DeliveryShippedEvent)는 eventType 헤더로 걸러 payload를 읽지 않고 처리 기록도 남기지 않는다")
    void shippedEvent_ignoredByHeader() {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(
                new DeliveryShippedEvent(70L, 500L, 3L, "CJ대한통운", "123456789012", DELIVERED_AT.minusDays(2)));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.events", 0, 0, "70", "json");
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, orderFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 배송 시작 이벤트는 봉투를 읽어 거르고 처리 기록도 남기지 않는다")
    void noHeader_shippedEvent_ignored() {
        receive(new DeliveryShippedEvent(70L, 500L, 3L, "CJ대한통운", "123456789012", DELIVERED_AT.minusDays(2)), false, true);

        verifyNoInteractions(processedEvents, orderFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 다른 이벤트(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거르고 처리 기록도 남기지 않는다")
    void noHeader_otherEvent_ignored() {
        receive(new OrderPaidEvent(500L, "ORD-1", 3L, DELIVERED_AT, null, java.util.List.of()), false, true);

        verifyNoInteractions(processedEvents, orderFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 배송 완료 이벤트도 처리한다")
    void noHeader_completed_marksDelivered() {
        receive(new DeliveryCompletedEvent(70L, 500L, 3L, DELIVERED_AT), false, true);

        verify(orderFacade).markDelivered(500L, DELIVERED_AT);
    }

    private void receive(DomainEvent event, boolean withHeader, boolean firstTime) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.events", 0, 0, "70", "json");
        if (withHeader) {
            record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        }
        given(eventMessages.read("json")).willReturn(envelope);
        if (event instanceof DeliveryCompletedEvent) {
            given(processedEvents.markProcessed(envelope.eventId(), OrderDeliveryEventListener.CONSUMER)).willReturn(firstTime);
        }

        listener.handle(record);
    }
}
