package com.backend.boundedContext.delivery.in;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderPaidEvent;

import lombok.RequiredArgsConstructor;

/**
 * 주문 서비스의 결제 완료 이벤트를 Kafka(order.events)로 받아 출고 대기(READY) 배송을 만든다.
 *
 * 흐름: 주문 결제 → outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = delivery)
 * 한 메시지를 하나의 트랜잭션에서 처리한다(@Transactional).
 *   1. order.events에 함께 오는 다른 주문 이벤트(구매 확정 등)는 무시한다.
 *      eventType 헤더로 먼저 거르고(payload를 읽지 않음), 헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거른다
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 배송을 만든다. 같은 주문의 배송이 이미 있으면(eventId가 다른 중복 이벤트) 건너뛴다
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리한다.
 * 배송지·항목이 없거나 수량이 0 이하인 이벤트는 IllegalArgumentException → 재시도 없이 order.events-dlt (KafkaConsumerConfig)
 */
@Component
@RequiredArgsConstructor
public class DeliveryOrderEventListener {

    /** processed_event.consumer 값이자 Kafka groupId (서비스 이름). 같은 이벤트를 다른 서비스(정산 등)와 따로 처리한다 */
    static final String CONSUMER = "delivery";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = OrderPaidEvent.class.getSimpleName();

    private final DeliveryFacade deliveryFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "order.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않는다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPE.equals(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof OrderPaidEvent event)) return;           // 헤더 없이 온 다른 주문 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;   // 이미 처리한 이벤트
        deliveryFacade.createFromPaidOrder(event);
    }
}
