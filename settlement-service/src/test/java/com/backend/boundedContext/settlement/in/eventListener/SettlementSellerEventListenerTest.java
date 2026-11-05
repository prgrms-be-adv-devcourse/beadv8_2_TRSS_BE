package com.backend.boundedContext.settlement.in.eventListener;

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

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerSanctionedEvent;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;

/**
 * 판매자 이벤트 컨슈머가 제재 이벤트만 골라 정산을 보류하는지 확인함
 * Kafka·DB 없이 Facade·봉투 읽기·처리 기록을 mock으로 둔다
 */
@ExtendWith(MockitoExtension.class)
class SettlementSellerEventListenerTest {

    private static final long SELLER_ID = 7L;

    @Mock
    private SettlementFacade settlementFacade;

    @Mock
    private EventMessages eventMessages;

    @Mock
    private ProcessedEvents processedEvents;

    @InjectMocks
    private SettlementSellerEventListener listener;

    @Test
    @DisplayName("제재 이벤트를 받으면 그 판매자의 정산을 사유와 함께 보류한다")
    void sanctioned_holdsSettlements() {
        receive(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"), true);

        verify(settlementFacade).holdSellerBySanctionEvent(SELLER_ID, "위조 상품 판매");
    }

    @Test
    @DisplayName("이미 처리한 제재 이벤트면(재전달) 정산을 건드리지 않는다")
    void alreadyProcessed_skips() {
        receive(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"), false);

        verify(settlementFacade, never()).holdSellerBySanctionEvent(any(), anyString());
    }

    @Test
    @DisplayName("정산이 할 일이 없는 판매자 이벤트(철회 요청 등)는 헤더만 보고 payload를 읽지 않는다")
    void otherSellerEventHeader_ignored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "{}");
        record.headers().add("eventType",
                SellerWithdrawalRequestedEvent.class.getSimpleName().getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, settlementFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 다른 판매자 이벤트(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거르고 처리 기록도 남기지 않는다")
    void otherSellerEventWithoutHeader_ignored() {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(new SellerWithdrawalRequestedEvent(SELLER_ID, "폐업 예정"));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "json");
        given(eventMessages.read("json")).willReturn(envelope);

        listener.handle(record);

        verifyNoInteractions(processedEvents, settlementFacade);
    }

    private void receive(DomainEvent event, boolean firstTime) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "json");
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        given(eventMessages.read("json")).willReturn(envelope);
        given(processedEvents.markProcessed(envelope.eventId(), SettlementSellerEventListener.CONSUMER))
                .willReturn(firstTime);

        listener.handle(record);
    }
}
