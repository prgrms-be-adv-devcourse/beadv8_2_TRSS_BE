package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderConfirmedEvent;
import com.backend.shared.order.event.OrderPaidEvent;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationOrderEventListenerTest {

    private static final long ORDER_ID = 10L;
    private static final long BUYER_ID = 100L;

    @Mock
    private NotificationFacade notificationFacade;

    @Mock
    private EventMessages eventMessages;

    @Mock
    private ProcessedEvents processedEvents;

    @InjectMocks
    private NotificationOrderEventListener listener;

    @Test
    @DisplayName("결제 완료 이벤트를 처음 받으면 구매자에게 주문 완료 알림을 저장함")
    void orderPaid_createsNotification() {
        receive(paidEvent(), true, true);

        verify(notificationFacade).createOrderPaid(BUYER_ID, ORDER_ID);
    }

    @Test
    @DisplayName("이미 처리한 이벤트면 알림을 저장하지 않음")
    void alreadyProcessed_skips() {
        receive(paidEvent(), false, true);

        verify(notificationFacade, never()).createOrderPaid(any(), any());
    }

    @Test
    @DisplayName("다루지 않는 이벤트 타입 헤더(OrderConfirmedEvent)면 payload를 읽지 않고 무시함")
    void otherEventTypeHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", "{}");
        record.headers().add("eventType", "OrderConfirmedEvent".getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, notificationFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 다른 주문 이벤트는 봉투를 읽어 거르고 처리 기록을 남기지 않음")
    void otherEventWithoutHeader_ignored() {
        OrderConfirmedEvent other = new OrderConfirmedEvent(ORDER_ID, BUYER_ID, LocalDateTime.now(), ConfirmType.MANUAL, List.of());

        receive(other, true, false);

        verifyNoInteractions(processedEvents, notificationFacade);
    }

    @Test
    @DisplayName("헤더 없는 결제 완료 이벤트(DLT 재전송)도 처리함")
    void orderPaidWithoutHeader_createsNotification() {
        receive(paidEvent(), true, false);

        verify(notificationFacade).createOrderPaid(BUYER_ID, ORDER_ID);
    }

    private OrderPaidEvent paidEvent() {
        ShippingAddressDto address = new ShippingAddressDto("구매자", "01012345678", "12345", "서울시 강남구", "101호", null);
        PaidOrderItemDto item = new PaidOrderItemDto(1L, 2L, 3L, 4L, "판매자", "상품", "옵션", 1);
        return new OrderPaidEvent(ORDER_ID, "ORD-1", BUYER_ID, LocalDateTime.now(), address, List.of(item));
    }

    private void receive(DomainEvent event, boolean firstTime, boolean withHeader) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0, "10", "json");
        if (withHeader) {
            record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        }
        given(eventMessages.read("json")).willReturn(envelope);
        if (event instanceof OrderPaidEvent) {
            given(processedEvents.markProcessed(envelope.eventId(), NotificationOrderEventListener.CONSUMER)).willReturn(firstTime);
        }

        listener.handle(record);
    }
}
