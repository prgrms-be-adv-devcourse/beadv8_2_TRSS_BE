package com.backend.boundedContext.order.in;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;

import lombok.RequiredArgsConstructor;

/**
 * delivery 서비스의 배송 완료 이벤트를 Kafka(delivery.events)로 받아 주문의 배송 완료 시각을 기록한다.
 *
 * 흐름: 배송 완료(택배사 조회) → delivery outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = market)
 * 메시지 키가 deliveryId라 같은 배송 건 이벤트는 보낸 순서대로 받는다.
 * 한 메시지를 하나의 트랜잭션에서 처리한다(@Transactional).
 *   1. delivery.events에 함께 오는 다른 배송 이벤트는 eventType 헤더로 먼저 거른다(payload를 읽지 않음).
 *      헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거른다
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 주문을 잠가서 배송 완료 시각을 기록한다(이미 있으면 처음 값 유지). 없는 주문은 WARN 후 넘긴다(DLT 아님)
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리한다.
 * 실패한 메시지는 1·2·4·8초 간격으로 다시 받고(총 5번), 그래도 실패하면 delivery.events-dlt로 간다(KafkaConsumerConfig).
 */
@Component
@RequiredArgsConstructor
public class OrderDeliveryEventListener {

    /** processed_event.consumer 값이자 Kafka groupId. market의 다른 컨슈머(ProductSellerEventListener)와 같은 서비스 이름을 쓴다 */
    static final String CONSUMER = "market";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = DeliveryCompletedEvent.class.getSimpleName();

    private final OrderFacade orderFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "delivery.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않는다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPE.equals(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof DeliveryCompletedEvent event)) return;    // 헤더 없이 온 다른 배송 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;     // 이미 처리한 이벤트

        orderFacade.markDelivered(event.orderId(), event.deliveredAt());
    }
}
