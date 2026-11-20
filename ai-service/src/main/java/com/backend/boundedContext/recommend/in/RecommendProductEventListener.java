package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.product.event.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * market의 상품 변경 이벤트를 Kafka(product.events)로 받아 추천용 상품 사본에 반영한다.
 * 한 메시지를 하나의 트랜잭션에서 처리한다(NotificationOrderEventListener와 같은 순서).
 *   1. product.events에 함께 오는 찜 이벤트 등은 eventType 헤더로 먼저 거른다(payload를 읽지 않음)
 *   2. 같은 트랜잭션에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 사본을 반영한다. 임베딩은 여기서 만들지 않는다(OpenAI가 실패해도 판매 중지 같은 상태 반영은 되도록, 스케줄러가 채운다)
 */
@Component
@RequiredArgsConstructor
public class RecommendProductEventListener {

    // processed_event.consumer 값이자 Kafka groupId
    static final String CONSUMER = "ai";

    // OutboxKafkaSender가 붙이는 이벤트 타입 헤더
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = ProductChangedEvent.class.getSimpleName();

    private final RecommendFacade recommendFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "product.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않는다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPE.equals(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof ProductChangedEvent event)) return;    // 헤더 없이 온 다른 상품 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;  // 이미 처리한 이벤트
        recommendFacade.applyProductChange(event, envelope.occurredAt());
    }
}
