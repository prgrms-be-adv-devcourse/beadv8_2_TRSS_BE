package com.backend.boundedContext.product.in;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerSanctionedEvent;
import com.backend.shared.member.event.SellerWithdrawalRejectedEvent;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 서비스의 판매자 이벤트를 Kafka(seller.events)로 받아 판매자 상품을 중지·재개한다.
 *
 * 흐름: 판매자 제재·철회 요청·철회 거절 → outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = market)
 * 메시지 키가 sellerId라 같은 판매자 이벤트는 보낸 순서대로 받는다.
 * 한 메시지를 하나의 트랜잭션에서 처리한다(@Transactional).
 *   1. seller.events에 함께 오는 다른 판매자 이벤트는 무시한다. eventType 헤더로 먼저 거르고(payload를 읽지 않음),
 *      헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거른다
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 제재·철회 요청이면 판매 중 상품을 모두 중지하고, 철회 거절이면 철회로 중지된 상품을 다시 판매 중으로 되돌린다
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리한다.
 * 실패한 메시지는 1·2·4·8초 간격으로 다시 받고(총 5번), 그래도 실패하면 seller.events-dlt로 간다(KafkaConsumerConfig).
 * 중지·재개는 다시 호출해도 결과가 같으므로 이중으로 처리돼도 문제없다(ProductApi 참고).
 */
@Component
@RequiredArgsConstructor
public class ProductSellerEventListener {

    /** processed_event.consumer 값이자 Kafka groupId. 같은 이벤트를 다른 서비스(정산 등)와 따로 처리한다 */
    static final String CONSUMER = "market";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final Set<String> HANDLED_EVENT_TYPES = Set.of(
            SellerSanctionedEvent.class.getSimpleName(),
            SellerWithdrawalRequestedEvent.class.getSimpleName(),
            SellerWithdrawalRejectedEvent.class.getSimpleName()
    );

    private final ProductFacade productFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "seller.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않는다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPES.contains(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!HANDLED_EVENT_TYPES.contains(envelope.eventType())) return;               // 헤더 없이 온 다른 판매자 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;     // 이미 처리한 이벤트

        switch (envelope.payload()) {
            case SellerSanctionedEvent event -> productFacade.stopAllBySeller(event.sellerId(), event.reason());
            case SellerWithdrawalRequestedEvent event -> productFacade.stopAllBySeller(event.sellerId(), event.reason());
            case SellerWithdrawalRejectedEvent event -> productFacade.resumeAllBySeller(event.sellerId());
            default -> throw new IllegalStateException("처리할 수 없는 판매자 이벤트: " + envelope.eventType());
        }
    }
}
