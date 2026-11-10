package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerSanctionedEvent;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CouponSellerEventListenerTest {

    private static final long SELLER_ID = 7L;

    @Mock private CouponFacade couponFacade;
    @Mock private EventMessages eventMessages;
    @Mock private ProcessedEvents processedEvents;

    @InjectMocks private CouponSellerEventListener listener;

    @Test
    @DisplayName("제재 이벤트를 받으면 판매자의 협약을 모두 종료한다 (groupId, 처리 기록은 market-coupon)")
    void sanctioned() {
        receive(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"), true);

        verify(couponFacade).endPartnershipsBySellerSanction(SELLER_ID);
    }

    @Test
    @DisplayName("이미 처리한 제재 이벤트면 협약을 건드리지 않는다")
    void alreadyProcessed() {
        receive(new SellerSanctionedEvent(SELLER_ID, "위조 상품 판매"), false);

        verify(couponFacade, never()).endPartnershipsBySellerSanction(anyLong());
    }

    @Test
    @DisplayName("철회 요청 이벤트는 헤더만 보고 무시한다 (상품 중지로 쿠폰이 중지되고, 협약은 철회가 거절될 수 있어 둔다)")
    void withdrawalRequestedIgnored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "json");
        record.headers().add("eventType",
                SellerWithdrawalRequestedEvent.class.getSimpleName().getBytes(StandardCharsets.UTF_8));

        listener.handle(record);

        verifyNoInteractions(eventMessages, processedEvents, couponFacade);
    }

    @Test
    @DisplayName("헤더 없이 온 다른 판매자 이벤트는 봉투를 읽어 거르고 처리 기록을 남기지 않는다")
    void otherEventWithoutHeaderIgnored() {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(new SellerWithdrawalRequestedEvent(SELLER_ID, "폐업"));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "json");
        given(eventMessages.read("json")).willReturn(envelope);

        listener.handle(record);

        verifyNoInteractions(processedEvents, couponFacade);
    }

    private void receive(DomainEvent event, boolean firstTime) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("seller.events", 0, 0, "7", "json");
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        given(eventMessages.read("json")).willReturn(envelope);
        given(processedEvents.markProcessed(envelope.eventId(), CouponSellerEventListener.CONSUMER)).willReturn(firstTime);

        listener.handle(record);
    }
}
