package com.backend.shared.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.member.event.MemberSignedUpEvent;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.order.event.OrderConfirmedEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EventEnvelopeTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final OrderConfirmedEvent orderConfirmed = new OrderConfirmedEvent(
            10L, 3L, LocalDateTime.of(2026, 10, 27, 9, 0), ConfirmType.MANUAL,
            List.of(new ConfirmedOrderItemDto(100L, 7L, 2L, 5000L, 2, 10000L)));

    @Test
    @DisplayName("봉투에 담으면 새 eventId, 클래스 이름 eventType, 집합 정보가 채워진다")
    void wrap() {
        LocalDateTime before = LocalDateTime.now();

        EventEnvelope<OrderConfirmedEvent> envelope = EventEnvelope.wrap(orderConfirmed);

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.eventType()).isEqualTo("OrderConfirmedEvent");
        assertThat(envelope.occurredAt()).isAfterOrEqualTo(before);
        assertThat(envelope.aggregateType()).isEqualTo("ORDER");
        assertThat(envelope.aggregateId()).isEqualTo(10L);
        assertThat(envelope.payload()).isSameAs(orderConfirmed);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 담으면 eventId가 다르다")
    void wrapTwice() {
        assertThat(EventEnvelope.wrap(orderConfirmed).eventId())
                .isNotEqualTo(EventEnvelope.wrap(orderConfirmed).eventId());
    }

    @Test
    @DisplayName("회원 가입 이벤트는 MEMBER 집합, 회원 ID로 담긴다")
    void wrapMemberSignedUp() {
        EventEnvelope<MemberSignedUpEvent> envelope = EventEnvelope.wrap(new MemberSignedUpEvent(5L));

        assertThat(envelope.eventType()).isEqualTo("MemberSignedUpEvent");
        assertThat(envelope.aggregateType()).isEqualTo("MEMBER");
        assertThat(envelope.aggregateId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("집합 ID가 없는 이벤트는 담을 수 없다")
    void wrapWithoutAggregateId() {
        assertThatThrownBy(() -> EventEnvelope.wrap(new MemberSignedUpEvent(null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("aggregateId");
    }

    @Test
    @DisplayName("payload JSON에는 record 필드만 들어가고 aggregateType·aggregateId는 섞이지 않는다")
    void payloadJson() {
        JsonNode json = jsonMapper.valueToTree(orderConfirmed);

        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("orderId", "buyerId", "confirmedAt", "confirmType", "items");
    }

    @Test
    @DisplayName("payload JSON을 다시 읽으면 원래 이벤트와 같다")
    void payloadRoundTrip() {
        String json = jsonMapper.writeValueAsString(orderConfirmed);

        assertThat(jsonMapper.readValue(json, OrderConfirmedEvent.class)).isEqualTo(orderConfirmed);
    }
}
