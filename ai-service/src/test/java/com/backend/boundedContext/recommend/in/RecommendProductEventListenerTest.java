package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.product.event.ProductChangedEvent;
import com.backend.shared.product.event.WishAddedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RecommendProductEventListenerTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 15, 12, 0);
    private static final UUID EVENT_ID = UUID.randomUUID();

    private final RecommendFacade recommendFacade = mock(RecommendFacade.class);
    private final EventMessages eventMessages = mock(EventMessages.class);
    private final ProcessedEvents processedEvents = mock(ProcessedEvents.class);
    private final RecommendProductEventListener listener = new RecommendProductEventListener(recommendFacade, eventMessages, processedEvents);

    private final ProductChangedEvent event = new ProductChangedEvent(1L, "수분 크림", "설명", "스킨케어", true, "정제수", 25_000, null, true);

    @Test
    @DisplayName("상품 변경 이벤트는 발생 시각과 함께 반영한다")
    void handle() {
        given(eventMessages.read("json")).willReturn(envelope(event));
        given(processedEvents.markProcessed(EVENT_ID, RecommendProductEventListener.CONSUMER)).willReturn(true);

        listener.handle(record("ProductChangedEvent"));

        verify(recommendFacade).applyProductChange(event, OCCURRED_AT);
    }

    @Test
    @DisplayName("다른 이벤트 타입 헤더(찜 등)는 payload를 읽지 않고 무시한다")
    void otherEventTypeIgnored() {
        listener.handle(record("WishAddedEvent"));

        verifyNoInteractions(eventMessages, processedEvents, recommendFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 다른 상품 이벤트도 무시한다")
    void otherPayloadIgnored() {
        given(eventMessages.read("json")).willReturn(envelope(new WishAddedEvent(3L, 1L)));

        listener.handle(record(null));

        verifyNoInteractions(processedEvents, recommendFacade);
    }

    @Test
    @DisplayName("이미 처리한 eventId(재전달)는 반영하지 않는다")
    void duplicateIgnored() {
        given(eventMessages.read("json")).willReturn(envelope(event));
        given(processedEvents.markProcessed(EVENT_ID, RecommendProductEventListener.CONSUMER)).willReturn(false);

        listener.handle(record("ProductChangedEvent"));

        verify(recommendFacade, never()).applyProductChange(any(), any());
    }

    private static ConsumerRecord<String, String> record(String eventType) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("product.events", 0, 0L, "1", "json");
        if (eventType != null) record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static EventEnvelope<DomainEvent> envelope(DomainEvent payload) {
        return new EventEnvelope<>(EVENT_ID, payload.getClass().getSimpleName(), OCCURRED_AT, "PRODUCT", 1L, payload);
    }
}
