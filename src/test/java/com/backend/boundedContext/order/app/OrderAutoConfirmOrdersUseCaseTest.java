package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderAutoConfirmOrdersUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock EventPublisher eventPublisher;

    OrderAutoConfirmOrdersUseCase useCase;

    private static final Long ORDER_ID = 500L;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    @BeforeEach
    void setUp() {
        // OrderPolicy는 값 객체라 실제로 만들고(autoConfirmDays 7), 이벤트 발행까지 보려고 OrderConfirmSupport도 실제 객체를 쓴다
        useCase = new OrderAutoConfirmOrdersUseCase(orderRepository, new OrderPolicy(15, 7, 99, 100),
                new OrderConfirmSupport(eventPublisher));
    }

    /** PAID_AT에 결제 완료된 주문 */
    private Order paidOrder() {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), PAID_AT.minusMinutes(1), 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.pay(PAID_AT);
        return order;
    }

    @Test
    @DisplayName("findTargetIds: 지금 - 7일 이전에 결제된 PAID 주문을 afterId 다음부터 limit건 조회한다")
    void findTargetIds() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 0);
        given(orderRepository.findAutoConfirmTargetIds(eq(OrderStatus.PAID), eq(now.minusDays(7)), eq(300L),
                argThat((Pageable p) -> p.getPageNumber() == 0 && p.getPageSize() == 100)))
                .willReturn(List.of(301L, 302L));

        assertThat(useCase.findTargetIds(now, 300L, 100)).containsExactly(301L, 302L);
    }

    @Test
    @DisplayName("autoConfirm: 결제 후 7일이 지난 PAID 주문이면 CONFIRMED·AUTO로 확정하고 AUTO 유형으로 이벤트를 발행한다")
    void autoConfirm() {
        Order order = paidOrder();
        LocalDateTime now = PAID_AT.plusDays(7);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        boolean result = useCase.autoConfirm(ORDER_ID, now);

        assertThat(result).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmType()).isEqualTo(ConfirmType.AUTO);
        assertThat(order.getConfirmedAt()).isEqualTo(now);

        ArgumentCaptor<OrderConfirmedEvent> captor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().confirmType()).isEqualTo(ConfirmType.AUTO);
    }

    @Test
    @DisplayName("autoConfirm: 조회 후 반품 요청이 먼저 들어와 RETURN_REQUESTED면 건너뛰고 이벤트를 발행하지 않는다")
    void autoConfirm_returnRequested() {
        Order order = paidOrder();
        ReflectionTestUtils.setField(order, "status", OrderStatus.RETURN_REQUESTED);   // 반품 요청 기능 추가 전이라 상태만 바꿈
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        assertThat(useCase.autoConfirm(ORDER_ID, PAID_AT.plusDays(8))).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("autoConfirm: 조회 후 구매자가 먼저 수동 확정했으면 건너뛰고 이벤트를 다시 발행하지 않는다")
    void autoConfirm_alreadyConfirmed() {
        Order order = paidOrder();
        order.confirm(ConfirmType.MANUAL, PAID_AT.plusDays(3));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        assertThat(useCase.autoConfirm(ORDER_ID, PAID_AT.plusDays(8))).isFalse();
        assertThat(order.getConfirmType()).isEqualTo(ConfirmType.MANUAL);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("autoConfirm: 아직 7일이 지나지 않았으면 건너뛴다")
    void autoConfirm_notYet() {
        Order order = paidOrder();
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        assertThat(useCase.autoConfirm(ORDER_ID, PAID_AT.plusDays(7).minusSeconds(1))).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("autoConfirm: 주문이 없으면 건너뛴다")
    void autoConfirm_notFound() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.empty());

        assertThat(useCase.autoConfirm(ORDER_ID, PAID_AT.plusDays(8))).isFalse();
        verifyNoInteractions(eventPublisher);
    }
}
