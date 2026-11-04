package com.backend.boundedContext.product.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerSanctionedEvent;
import com.backend.shared.member.event.SellerWithdrawalRejectedEvent;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;

@ExtendWith(MockitoExtension.class)
class ProductSellerEventListenerTest {

    private static final long SELLER_ID = 7L;

    @Mock
    private ProductFacade productFacade;

    @Mock
    private EventMessages eventMessages;

    @Mock
    private ProcessedEvents processedEvents;

    @InjectMocks
    private ProductSellerEventListener listener;

    @Test
    @DisplayName("제재 이벤트를 받으면 판매자 상품을 사유와 함께 중지한다")
    void sanctioned_stopsProducts() {
        receive(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"), true);

        verify(productFacade).stopAllBySeller(SELLER_ID, "위조 상품 판매");
    }

    @Test
    @DisplayName("철회 요청 이벤트를 받으면 판매자 상품을 사유와 함께 중지한다")
    void withdrawalRequested_stopsProducts() {
        receive(new SellerWithdrawalRequestedEvent(SELLER_ID, "폐업 예정"), true);

        verify(productFacade).stopAllBySeller(SELLER_ID, "폐업 예정");
    }

    @Test
    @DisplayName("철회 거절 이벤트를 받으면 철회로 중지된 상품을 다시 판매 중으로 되돌린다")
    void withdrawalRejected_resumesProducts() {
        receive(new SellerWithdrawalRejectedEvent(SELLER_ID), true);

        verify(productFacade).resumeAllBySeller(SELLER_ID);
    }

    @Test
    @DisplayName("이미 처리한 이벤트면 상품을 건드리지 않는다")
    void alreadyProcessed_skips() {
        receive(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"), false);

        verify(productFacade, never()).stopAllBySeller(any(), anyString());
    }

    @Test
    @DisplayName("다루지 않는 이벤트 타입 헤더면 payload를 읽지 않고 무시한다")
    void otherEventTypeHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "{}");
        record.headers().add("eventType", "SellerApprovedEvent".getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, productFacade);
    }

    private void receive(DomainEvent event, boolean firstTime) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "json");
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        given(eventMessages.read("json")).willReturn(envelope);
        given(processedEvents.markProcessed(envelope.eventId(), ProductSellerEventListener.CONSUMER)).willReturn(firstTime);

        listener.handle(record);
    }
}
