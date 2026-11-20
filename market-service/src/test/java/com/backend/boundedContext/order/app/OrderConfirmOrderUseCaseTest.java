package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.OrderConfirmResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.event.OrderConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderConfirmOrderUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock EventPublisher eventPublisher;

    OrderConfirmOrderUseCase useCase;

    private static final Long BUYER_ID = 3L;
    private static final Long ORDER_ID = 500L;

    @BeforeEach
    void setUp() {
        // 확정 + 이벤트 발행까지 실제 흐름으로 검증하려고 OrderConfirmSupport는 실제 객체를 쓴다
        useCase = new OrderConfirmOrderUseCase(orderRepository, new OrderConfirmSupport(eventPublisher));
    }

    /** 옵션 100(10,000원 × 2)으로 만든 주문 */
    private Order order() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("결제 완료 주문을 확정하면 CONFIRMED·MANUAL로 응답하고 MANUAL 유형으로 OrderConfirmedEvent를 발행한다")
    void confirm() {
        Order order = order();
        order.pay(LocalDateTime.now());
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        OrderConfirmResponse response = useCase.confirm(BUYER_ID, ORDER_ID);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.confirmType()).isEqualTo(ConfirmType.MANUAL);
        assertThat(response.confirmedAt()).isNotNull();

        ArgumentCaptor<OrderConfirmedEvent> captor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().confirmType()).isEqualTo(ConfirmType.MANUAL);
        assertThat(captor.getValue().items()).hasSize(1);
    }

    @Test
    @DisplayName("내 주문이 아니거나 없으면 404-ORDER_NOT_FOUND이고 이벤트를 발행하지 않는다")
    void notFound() {
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.confirm(BUYER_ID, ORDER_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "404-ORDER_NOT_FOUND");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("결제 전 주문이면 409-ORDER_NOT_CONFIRMABLE이고 이벤트를 발행하지 않는다")
    void notPaid() {
        Order order = order();   // CREATED
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> useCase.confirm(BUYER_ID, ORDER_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CONFIRMABLE");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("이미 구매 확정된 주문이면 409-ORDER_NOT_CONFIRMABLE이다")
    void alreadyConfirmed() {
        Order order = order();
        order.pay(LocalDateTime.now());
        order.confirm(ConfirmType.AUTO, LocalDateTime.now());
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> useCase.confirm(BUYER_ID, ORDER_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CONFIRMABLE");
        verifyNoInteractions(eventPublisher);
    }
}
