package com.backend.boundedContext.settlement.in.eventListener;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerSanctionedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 서비스의 판매자 제재 이벤트를 Kafka(seller.events)로 받아 그 판매자의 지급 전 정산서를 보류한다.
 *
 * 흐름: 관리자 판매자 제재 → outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = settlement)
 * 메시지 키가 sellerId라 같은 판매자 이벤트는 보낸 순서대로 받는다.
 * 한 메시지를 하나의 트랜잭션에서 처리한다(@Transactional).
 *   1. seller.events에 함께 오는 다른 판매자 이벤트(철회 요청·철회 거절 등)는 정산이 할 일이 없으므로 무시한다.
 *      eventType 헤더로 먼저 거르고(payload를 읽지 않음), 헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거른다
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 지급 전 정산서(READY·FAILED·MANUAL_REQUIRED)를 HOLD로 바꾸고 처리 이력을 EVENT로 남긴다
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리한다.
 * 실패한 메시지는 1·2·4·8초 간격으로 다시 받고(총 5번), 그래도 실패하면 seller.events-dlt로 간다(KafkaConsumerConfig).
 * 보류는 이미 HOLD인 정산서를 건너뛰므로 이중으로 처리돼도 결과가 같다.
 *
 * 이벤트가 도착하기 전(보통 1초 이내, Kafka가 멈추면 더 길게)에 지급이 돌 수 있는 틈은
 * SettlementPayoutUseCase가 지급 직전에 판매자 상태를 다시 보고 SANCTIONED면 지급하지 않고 보류해 막는다.
 * 제재 뒤에 새로 만들어지는 정산서는 월 정산 집계가 판매자 상태를 보고 처음부터 HOLD로 만든다(SettlementAggregateUseCase).
 */
@Component
@RequiredArgsConstructor
public class SettlementSellerEventListener {

    /** processed_event.consumer 값이자 Kafka groupId. 같은 이벤트를 다른 서비스(상품 = market)와 따로 처리한다 */
    static final String CONSUMER = "settlement";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = SellerSanctionedEvent.class.getSimpleName();

    private final SettlementFacade settlementFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "seller.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않는다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPE.equals(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof SellerSanctionedEvent event)) return;       // 헤더 없이 온 다른 판매자 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;     // 이미 처리한 이벤트
        settlementFacade.holdSellerBySanctionEvent(event.sellerId(), event.reason());
    }
}
