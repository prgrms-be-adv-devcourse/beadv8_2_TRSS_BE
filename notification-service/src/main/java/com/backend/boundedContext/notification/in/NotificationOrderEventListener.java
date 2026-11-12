package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderPaidEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 주문 서비스의 결제 완료 이벤트를 Kafka(order.events)로 받아 구매자에게 주문 완료 알림을 저장함
 *
 * 흐름: 주문 결제 → outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = notification)
 * 한 메시지를 하나의 트랜잭션에서 처리함(@Transactional).
 *   1. order.events에 함께 오는 다른 주문 이벤트(구매 확정 등)는 무시함.
 *      eventType 헤더로 먼저 거르고(payload를 읽지 않음), 헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거름
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록함. 이미 있으면(재전달) 아무것도 하지 않음
 *   3. 알림을 저장함
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리함.
 * 알림이 실패해도 주문 결제는 이미 커밋된 뒤라 되돌리지 않음.
 */
@Component
@RequiredArgsConstructor
public class NotificationOrderEventListener {

    // processed_event.consumer 값이자 Kafka groupId (서비스 이름). 같은 이벤트를 다른 서비스(배송·정산)와 따로 처리함
    static final String CONSUMER = "notification";

    // OutboxKafkaSender가 붙이는 이벤트 타입 헤더
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = OrderPaidEvent.class.getSimpleName();

    private final NotificationFacade notificationFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "order.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거름. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않음
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPE.equals(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof OrderPaidEvent event)) return;         // 헤더 없이 온 다른 주문 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;  // 이미 처리한 이벤트
        // 대표 상품명은 첫 주문 항목, 개수는 주문 항목(상품 줄) 수
        String productName = event.items().isEmpty() ? null : event.items().getFirst().productName();
        notificationFacade.createOrderPaid(event.buyerId(), event.orderId(), productName, event.items().size());
    }
}
