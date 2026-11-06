package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.order.event.OrderPaidEvent;

class OutboxEventTypesTest {

    private final OutboxEventTypes outboxEventTypes = new OutboxEventTypes();

    @Test
    @DisplayName("배송 계약 이벤트를 단순 이름으로 찾는다")
    void findDeliveryContractEvents() {
        assertThat(outboxEventTypes.find("OrderPaidEvent")).contains(OrderPaidEvent.class);
        assertThat(outboxEventTypes.find("DeliveryCompletedEvent")).contains(DeliveryCompletedEvent.class);
    }

    @Test
    @DisplayName("모르는 이름이면 empty")
    void unknown() {
        assertThat(outboxEventTypes.find("NoSuchEvent")).isEmpty();
    }
}
