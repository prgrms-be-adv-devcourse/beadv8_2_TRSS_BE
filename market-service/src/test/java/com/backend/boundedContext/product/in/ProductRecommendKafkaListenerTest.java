package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderReturnedEvent;
import com.backend.shared.product.event.ProductChangedEvent;
import com.backend.shared.product.event.WishAddedEvent;
import com.backend.shared.review.event.ReviewCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 추천 다시 계산 컨슈머: 묶음에서 회원 ID를 모아 한 번씩 계산하고, 다루지 않는 이벤트·회원 ID 없는 예전 리뷰 이벤트는 건너뛴다 */
class ProductRecommendKafkaListenerTest {

    private final ProductFacade productFacade = mock(ProductFacade.class);
    private final EventMessages eventMessages = mock(EventMessages.class);
    private final ProductRecommendKafkaListener listener = new ProductRecommendKafkaListener(productFacade, eventMessages);

    @Test
    @DisplayName("같은 회원의 이벤트가 여러 개여도 한 번만 계산하고, 한 회원의 계산이 실패해도 다음 회원을 계산한다")
    void dedupeAndContinue() {
        willThrow(new IllegalStateException("ES 오류")).given(productFacade).refreshMemberRecommendations(1L);

        listener.handle(List.of(
                record("product.events", "WishAddedEvent", new WishAddedEvent(1L, 10L)),
                record("review.events", "ReviewCreatedEvent", new ReviewCreatedEvent(5L, 10L, 5, 1L)),
                record("order.events", "OrderReturnedEvent", new OrderReturnedEvent(3L, 2L, 1_000L, LocalDateTime.now()))));

        InOrder order = inOrder(productFacade);
        order.verify(productFacade).refreshMemberRecommendations(1L);
        order.verify(productFacade).refreshMemberRecommendations(2L);
        verify(productFacade, times(2)).refreshMemberRecommendations(any());
    }

    @Test
    @DisplayName("다루지 않는 이벤트(헤더로 거름)와 회원 ID가 없는 예전 리뷰 이벤트는 건너뛴다")
    void skipUnhandled() {
        listener.handle(List.of(
                record("product.events", "ProductChangedEvent", mock(ProductChangedEvent.class)),
                record("review.events", "ReviewCreatedEvent", new ReviewCreatedEvent(5L, 10L, 5, null))));

        verify(productFacade, never()).refreshMemberRecommendations(any());
    }

    private ConsumerRecord<String, String> record(String topic, String eventType, DomainEvent payload) {
        String value = UUID.randomUUID().toString();   // 봉투는 mock이 돌려준다
        EventEnvelope<DomainEvent> envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, LocalDateTime.now(),
                "X", 1L, payload);
        given(eventMessages.read(value)).willReturn(envelope);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, "1", value);
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
