package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.delivery.event.DeliveryShippedEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 배송 서비스의 배송 시작·완료 이벤트를 Kafka(delivery.events)로 받아 구매자에게 배송 알림을 저장함
 *
 * 흐름: 송장 등록·배송 완료 확인 → delivery outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = notification)
 * 메시지 키가 deliveryId라 같은 배송 건 이벤트는 보낸 순서(시작 → 완료)대로 받음.
 * 한 메시지를 하나의 트랜잭션에서 처리함(@Transactional).
 *   1. 다루지 않는 배송 이벤트는 eventType 헤더로 먼저 거름(payload를 읽지 않음).
 *      헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거름
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록함. 이미 있으면(재전달) 아무것도 하지 않음
 *   3. 이벤트 종류에 맞는 알림을 저장함
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리함.
 * 알림이 실패해도 배송 상태 변경은 이미 커밋된 뒤라 되돌리지 않음.
 */
@Component
@RequiredArgsConstructor
public class NotificationDeliveryEventListener {

    // processed_event.consumer 값이자 Kafka groupId. 알림의 다른 컨슈머(NotificationOrderEventListener)와 같은 서비스 이름을 씀
    static final String CONSUMER = NotificationOrderEventListener.CONSUMER;

    // OutboxKafkaSender가 붙이는 이벤트 타입 헤더
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final Set<String> HANDLED_EVENT_TYPES = Set.of(
            DeliveryShippedEvent.class.getSimpleName(),
            DeliveryCompletedEvent.class.getSimpleName());

    private final NotificationFacade notificationFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "delivery.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거름. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않음
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPES.contains(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        DomainEvent payload = envelope.payload();
        if (!(payload instanceof DeliveryShippedEvent || payload instanceof DeliveryCompletedEvent)) return;  // 헤더 없이 온 다른 배송 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;                             // 이미 처리한 이벤트

        if (payload instanceof DeliveryShippedEvent event) {
            notificationFacade.createDeliveryStarted(event.buyerId(), event.orderId(), event.carrierName(), event.trackingNumber());
        } else if (payload instanceof DeliveryCompletedEvent event) {
            notificationFacade.createDeliveryCompleted(event.buyerId(), event.orderId());
        }
    }
}
