package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.event.OrderConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderConfirmSupportTest {

    @Mock EventPublisher eventPublisher;
    @InjectMocks
    OrderConfirmSupport confirmSupport;

    private static final Long BUYER_ID = 3L;
    private static final Long ORDER_ID = 500L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 0);

    /** 판매자 2명 상품이 섞인 주문: 옵션 100(판매자 2, 10,000원 × 2), 옵션 200(판매자 7, 5,000원 × 1) */
    private Order order() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점A", 10_000, 2),
                new OrderItemSpec(2L, 20L, 200L, 7L, "틴트", "기본", "상점B", 5_000, 1)), NOW, 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", 1001L);
        ReflectionTestUtils.setField(order.getItems().get(1), "id", 1002L);
        return order;
    }

    @Test
    @DisplayName("결제 완료 주문을 확정하면 CONFIRMED가 되고, 확정 유형과 주문 항목 전체로 OrderConfirmedEvent를 발행한다")
    void confirm() {
        Order order = order();
        order.pay(NOW.plusMinutes(1));
        LocalDateTime confirmedAt = NOW.plusDays(1);

        confirmSupport.confirm(order, ConfirmType.MANUAL, confirmedAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        ArgumentCaptor<OrderConfirmedEvent> captor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        OrderConfirmedEvent event = captor.getValue();

        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.buyerId()).isEqualTo(BUYER_ID);
        assertThat(event.confirmedAt()).isEqualTo(confirmedAt);
        assertThat(event.confirmType()).isEqualTo(ConfirmType.MANUAL);
        assertThat(event.items()).containsExactly(
                new ConfirmedOrderItemDto(1001L, 10L, 2L, 10_000, 2, 20_000),
                new ConfirmedOrderItemDto(1002L, 20L, 7L, 5_000, 1, 5_000));
    }

    @Test
    @DisplayName("자동 확정도 같은 메서드로 처리되고 확정 유형 AUTO로 이벤트를 발행한다")
    void confirm_auto() {
        Order order = order();
        order.pay(NOW.plusMinutes(1));

        confirmSupport.confirm(order, ConfirmType.AUTO, NOW.plusDays(7));

        ArgumentCaptor<OrderConfirmedEvent> captor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().confirmType()).isEqualTo(ConfirmType.AUTO);
        assertThat(order.getConfirmType()).isEqualTo(ConfirmType.AUTO);
    }

    @Test
    @DisplayName("결제 완료가 아닌 주문이면 409-ORDER_NOT_CONFIRMABLE이고 이벤트를 발행하지 않는다")
    void confirm_notPaid() {
        Order order = order();   // CREATED

        assertThatThrownBy(() -> confirmSupport.confirm(order, ConfirmType.MANUAL, NOW.plusDays(1)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CONFIRMABLE");
        verifyNoInteractions(eventPublisher);
    }
}
