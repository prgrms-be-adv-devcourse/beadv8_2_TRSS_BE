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
 * delivery.events의 배송 완료 이벤트를 받아 주문의 배송 완료 시각을 기록한다 (groupId = market, 메시지 키 = deliveryId).
 * 한 메시지를 한 트랜잭션에서 처리한다.
 *   1. 다른 배송 이벤트는 eventType 헤더로 거른다(헤더가 없으면 봉투를 읽어 거른다)
 *   2. processed_event에 eventId를 기록한다. 이미 있으면(재전달) 건너뛴다
 *   3. 주문을 잠가 배송 완료 시각을 기록한다(이미 있으면 처음 값 유지, 없는 주문은 WARN 후 넘김)
 * 3이 실패하면 2도 롤백돼 다시 받을 때 처음부터 처리한다. 5번 실패하면 delivery.events-dlt로 간다(KafkaConsumerConfig).
 */
@Component
@RequiredArgsConstructor
public class OrderDeliveryEventListener {

    /**
     * Kafka groupId. 토픽이 다른 ProductSellerEventListener와 같은 group(market)을 그대로 쓴다.
     * 바꾸면 새 consumer group이 되어 auto-offset-reset(earliest)에 따라 delivery.events를 처음부터 다시 받는다
     */
    static final String GROUP_ID = "market";

    /** processed_event.consumer 값. 리스너마다 따로 둔다 (V21에서 이전 값 market의 기록을 옮겼다) */
    static final String CONSUMER = "market-order-delivery";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = DeliveryCompletedEvent.class.getSimpleName();

    private final OrderFacade orderFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "delivery.events", groupId = GROUP_ID)
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
