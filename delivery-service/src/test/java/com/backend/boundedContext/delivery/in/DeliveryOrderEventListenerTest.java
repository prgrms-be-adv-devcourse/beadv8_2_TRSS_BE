package com.backend.boundedContext.delivery.in;

import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.event.OrderConfirmedEvent;
import com.backend.shared.order.event.OrderPaidEvent;

@ExtendWith(MockitoExtension.class)
class DeliveryOrderEventListenerTest {

    @Mock
    private DeliveryFacade deliveryFacade;

    @Mock
    private EventMessages eventMessages;

    @Mock
    private ProcessedEvents processedEvents;

    @InjectMocks
    private DeliveryOrderEventListener listener;

    @Test
    @DisplayName("결제 완료 이벤트를 처음 받으면 배송을 만든다")
    void orderPaid_createsDelivery() {
        OrderPaidEvent event = paidEvent(10L, 100L);

        receive(event, true, true);

        verify(deliveryFacade).createFromPaidOrder(event);
    }

    @Test
    @DisplayName("이미 처리한 이벤트면 배송을 만들지 않는다")
    void alreadyProcessed_skips() {
        receive(paidEvent(10L, 100L), false, true);

        verify(deliveryFacade, never()).createFromPaidOrder(any());
    }

    @Test
    @DisplayName("다루지 않는 이벤트 타입 헤더(OrderConfirmedEvent)면 payload를 읽지 않고 무시한다")
    void otherEventTypeHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", "{}");
        record.headers().add("eventType", "OrderConfirmedEvent".getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, deliveryFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 다른 주문 이벤트는 봉투를 읽어 거르고 처리 기록을 남기지 않는다")
    void otherEventWithoutHeader_ignored() {
        OrderConfirmedEvent other = new OrderConfirmedEvent(10L, 77L, LocalDateTime.now(), ConfirmType.MANUAL, List.of());

        receive(other, true, false);

        verifyNoInteractions(processedEvents, deliveryFacade);
    }

    @Test
    @DisplayName("헤더 없는 결제 완료 이벤트(DLT 재전송)도 처리한다")
    void orderPaidWithoutHeader_createsDelivery() {
        OrderPaidEvent event = paidEvent(10L, 100L);

        receive(event, true, false);

        verify(deliveryFacade).createFromPaidOrder(event);
    }

    private void receive(DomainEvent event, boolean firstTime, boolean withHeader) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", "json");
        if (withHeader) {
            record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        }
        given(eventMessages.read("json")).willReturn(envelope);
        if (event instanceof OrderPaidEvent) {
            given(processedEvents.markProcessed(envelope.eventId(), DeliveryOrderEventListener.CONSUMER)).willReturn(firstTime);
        }

        listener.handle(record);
    }
}
