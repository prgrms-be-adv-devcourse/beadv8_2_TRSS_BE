package com.backend.boundedContext.settlement.in.eventListener;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 컨텍스트의 구매 확정 이벤트를 Kafka(order.events)로 받아 정산 후보를 만든다(TRSS-133 5-1 단계).
 *
 * 흐름: 주문 확정 → outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = settlement)
 * 한 메시지를 하나의 트랜잭션에서 처리한다(@Transactional).
 *   1. 봉투를 읽고, order.events에 함께 오는 다른 주문 이벤트는 무시한다
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 정산 후보를 만든다
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리한다.
 * 실패한 메시지는 1·2·4·8초 간격으로 다시 받고(총 5번), 그래도 실패하면 order.events-dlt로 간다(KafkaConsumerConfig).
 * order_item_id 유니크로 후보 중복을 한 번 더 막는다(이중 안전망).
 */
@Component
@RequiredArgsConstructor
public class SettlementEventListener {

    /** processed_event.consumer 값이자 Kafka groupId. 같은 이벤트를 다른 컨텍스트와 따로 처리한다 */
    static final String CONSUMER = "settlement";

    private final SettlementFacade settlementFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "order.events", groupId = CONSUMER)
    @Transactional
    public void handle(String message) {
        EventEnvelope<DomainEvent> envelope = eventMessages.read(message);
        if (!(envelope.payload() instanceof OrderConfirmedEvent event)) return;           // 다른 주문 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;       // 이미 처리한 이벤트
        settlementFacade.createCandidates(event.orderId(), event.confirmedAt(), event.items());
    }
}