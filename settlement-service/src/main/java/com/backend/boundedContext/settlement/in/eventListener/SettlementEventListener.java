package com.backend.boundedContext.settlement.in.eventListener;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 주문 컨텍스트의 구매 확정 이벤트를 Kafka(order.events)로 받아 정산 후보를 만든다(TRSS-133 5-1 단계).
 *
 * 흐름: 주문 확정 → outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = settlement)
 * 한 메시지를 하나의 트랜잭션에서 처리한다(@Transactional).
 *   1. order.events에 함께 오는 다른 주문 이벤트는 무시한다. eventType 헤더로 먼저 거르고(payload를 읽지 않음),
 *      헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투의 eventType만 읽어 거른다.
 *      그래서 이 앱이 아직 모르는 새 이벤트 타입이 헤더 없이 와도 역직렬화 실패로 DLT에 쌓이지 않는다
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 정산 후보를 만든다
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리한다.
 * 실패한 메시지는 1·2·4·8초 간격으로 다시 받고(총 5번), 그래도 실패하면 order.events-dlt로 간다(KafkaConsumerConfig).
 * order_item_id 유니크로 후보 중복을 한 번 더 막는다(이중 안전망).
 */
@Component
@RequiredArgsConstructor
public class SettlementEventListener {

    /** Kafka groupId. 정산의 모든 리스너가 같은 그룹을 쓴다(바꾸면 오프셋이 처음부터 다시 잡히므로 그대로 둔다) */
    static final String GROUP_ID = "settlement";

    /**
     * processed_event.consumer 값. 리스너 단위로 정한다(서비스.리스너).
     * 서비스 이름 하나로 두면 같은 서비스의 다른 리스너가 같은 이벤트를 따로 받을 때 먼저 처리한 쪽의 기록 때문에 건너뛴다
     * 예전 이름("settlement")으로 남은 기록은 옮기지 않는다. 이미 처리한 메시지는 오프셋이 커밋돼 다시 오지 않고,
     * DLT를 다시 보내 같은 이벤트가 와도 order_item_id 유니크와 후보 생성의 중복 건너뛰기가 막는다
     */
    static final String CONSUMER = "settlement.order-confirmed";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private final SettlementFacade settlementFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "order.events", groupId = GROUP_ID)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않는다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        String type = eventType != null
                ? new String(eventType.value(), StandardCharsets.UTF_8)
                : eventMessages.eventTypeOf(record.value());   // 헤더가 없으면 봉투의 eventType만 읽는다
        if (!OrderConfirmedEvent.class.getSimpleName().equals(type)) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof OrderConfirmedEvent event)) return;           // 타입 이름과 payload가 다르면 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;       // 이미 처리한 이벤트
        settlementFacade.createCandidates(event.orderId(), event.confirmedAt(), event.items());
    }
}