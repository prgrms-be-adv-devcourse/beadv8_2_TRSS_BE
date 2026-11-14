package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.delivery.event.DeliveryShippedEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.event.OrderConfirmedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryEventListenerTest {

    private static final long DELIVERY_ID = 5L;
    private static final long ORDER_ID = 10L;
    private static final long BUYER_ID = 100L;

    @Mock
    private NotificationFacade notificationFacade;

    @Mock
    private EventMessages eventMessages;

    @Mock
    private ProcessedEvents processedEvents;

    @InjectMocks
    private NotificationDeliveryEventListener listener;

    @Test
    @DisplayName("배송 시작 이벤트를 처음 받으면 택배사·송장번호를 담은 배송 시작 알림을 저장함")
    void shipped_createsDeliveryStarted() {
        receive(shippedEvent(), true, true);

        verify(notificationFacade).createDeliveryStarted(BUYER_ID, ORDER_ID, "CJ대한통운", "123456789012");
    }

    @Test
    @DisplayName("배송 완료 이벤트를 처음 받으면 배송 완료 알림을 저장함")
    void completed_createsDeliveryCompleted() {
        receive(completedEvent(), true, true);

        verify(notificationFacade).createDeliveryCompleted(BUYER_ID, ORDER_ID);
    }

    @Test
    @DisplayName("이미 처리한 이벤트면 알림을 저장하지 않음")
    void alreadyProcessed_skips() {
        receive(shippedEvent(), false, true);

        verifyNoInteractions(notificationFacade);
    }

    @Test
    @DisplayName("다루지 않는 이벤트 타입 헤더면 payload를 읽지 않고 무시함")
    void otherEventTypeHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.events", 0, 0, "5", "{}");
        record.headers().add("eventType", "DeliveryCanceledEvent".getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, notificationFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 다른 이벤트는 봉투를 읽어 거르고 처리 기록을 남기지 않음")
    void otherEventWithoutHeader_ignored() {
        OrderConfirmedEvent other = new OrderConfirmedEvent(ORDER_ID, BUYER_ID, LocalDateTime.now(), ConfirmType.MANUAL, List.of());

        receive(other, true, false);

        verifyNoInteractions(processedEvents, notificationFacade);
    }

    @Test
    @DisplayName("헤더 없는 배송 완료 이벤트(DLT 재전송)도 처리함")
    void completedWithoutHeader_createsDeliveryCompleted() {
        receive(completedEvent(), true, false);

        verify(notificationFacade).createDeliveryCompleted(BUYER_ID, ORDER_ID);
    }

    private DeliveryShippedEvent shippedEvent() {
        return new DeliveryShippedEvent(DELIVERY_ID, ORDER_ID, BUYER_ID, "CJ대한통운", "123456789012", LocalDateTime.now());
    }

    private DeliveryCompletedEvent completedEvent() {
        return new DeliveryCompletedEvent(DELIVERY_ID, ORDER_ID, BUYER_ID, LocalDateTime.now());
    }

    private void receive(DomainEvent event, boolean firstTime, boolean withHeader) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.events", 0, 0, "5", "json");
        if (withHeader) {
            record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        }
        given(eventMessages.read("json")).willReturn(envelope);
        if (event instanceof DeliveryShippedEvent || event instanceof DeliveryCompletedEvent) {
            given(processedEvents.markProcessed(envelope.eventId(), NotificationDeliveryEventListener.CONSUMER)).willReturn(firstTime);
        }

        listener.handle(record);
    }
}
