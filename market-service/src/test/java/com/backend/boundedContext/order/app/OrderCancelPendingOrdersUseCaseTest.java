package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCancelPendingOrdersUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;

    OrderCancelPendingOrdersUseCase useCase;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 16, 10, 0);
    private static final LocalDateTime NOW = CREATED_AT.plusMinutes(5);
    private static final Long STOPPED_PRODUCT_ID = 10L;
    private static final List<OrderStatus> PENDING = List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED);

    @BeforeEach
    void setUp() {
        // 재고 복원까지 확인하려고 OrderCancelSupport는 실제 객체를 쓴다
        useCase = new OrderCancelPendingOrdersUseCase(orderRepository, new OrderCancelSupport(productApi));
    }

    /** 결제 대기 주문. 항목은 (상품 ID, 옵션 ID, 수량) */
    private Order pendingOrder(Long orderId, long[]... items) {
        List<OrderItemSpec> specs = Arrays.stream(items)
                .map(i -> new OrderItemSpec(null, i[0], i[1], 2L, "상품" + i[0], "기본", "상점", 10_000, (int) i[2]))
                .toList();
        Order order = Order.create(3L, specs, CREATED_AT, 15);
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }

    @Test
    @DisplayName("중지된 상품이 들어 있는 결제 대기 주문을 PRODUCT_STOPPED로 취소하고, 다른 상품까지 주문 전체 재고를 복원한다")
    void cancel() {
        // 주문 501: 중지 상품(옵션 100) 2개 + 다른 상품(옵션 200) 1개
        Order order = pendingOrder(501L, new long[]{STOPPED_PRODUCT_ID, 100L, 2}, new long[]{20L, 200L, 1});
        given(orderRepository.findIdsByProductIdsAndStatusIn(Set.of(STOPPED_PRODUCT_ID), PENDING)).willReturn(List.of(501L));
        given(orderRepository.findAllByIdInForUpdate(List.of(501L))).willReturn(List.of(order));

        int canceled = useCase.cancel(Set.of(STOPPED_PRODUCT_ID), OrderCancelType.PRODUCT_STOPPED, NOW);

        assertThat(canceled).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.PRODUCT_STOPPED);
        assertThat(order.getCanceledAt()).isEqualTo(NOW);
        verify(productApi).restoreStock(
                argThat(items -> items.size() == 2
                        && items.stream().anyMatch(i -> i.optionId().equals(100L) && i.quantity() == 2)
                        && items.stream().anyMatch(i -> i.optionId().equals(200L) && i.quantity() == 1)),
                eq(StockRestoreReason.ORDER_RESTORE));
    }

    @Test
    @DisplayName("대상 주문이 여러 건이면 모두 취소하고 취소한 주문 수를 돌려준다")
    void cancel_many() {
        Order first = pendingOrder(501L, new long[]{STOPPED_PRODUCT_ID, 100L, 1});
        Order second = pendingOrder(502L, new long[]{STOPPED_PRODUCT_ID, 100L, 3});
        given(orderRepository.findIdsByProductIdsAndStatusIn(Set.of(STOPPED_PRODUCT_ID), PENDING)).willReturn(List.of(501L, 502L));
        given(orderRepository.findAllByIdInForUpdate(List.of(501L, 502L))).willReturn(List.of(first, second));

        int canceled = useCase.cancel(Set.of(STOPPED_PRODUCT_ID), OrderCancelType.PRODUCT_STOPPED, NOW);

        assertThat(canceled).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(second.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(productApi, times(2)).restoreStock(any(), eq(StockRestoreReason.ORDER_RESTORE));
    }

    @Test
    @DisplayName("잠그기 전에 결제가 끝나 PAID가 된 주문은 건너뛰고 재고를 복원하지 않는다")
    void cancel_skipPaid() {
        Order paid = pendingOrder(501L, new long[]{STOPPED_PRODUCT_ID, 100L, 1});
        paid.pay(NOW.minusMinutes(1));
        Order pending = pendingOrder(502L, new long[]{STOPPED_PRODUCT_ID, 100L, 1});
        given(orderRepository.findIdsByProductIdsAndStatusIn(Set.of(STOPPED_PRODUCT_ID), PENDING)).willReturn(List.of(501L, 502L));
        given(orderRepository.findAllByIdInForUpdate(List.of(501L, 502L))).willReturn(List.of(paid, pending));

        int canceled = useCase.cancel(Set.of(STOPPED_PRODUCT_ID), OrderCancelType.PRODUCT_STOPPED, NOW);

        assertThat(canceled).isEqualTo(1);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(productApi, times(1)).restoreStock(
                argThat(items -> items.stream().allMatch(i -> i.orderId().equals(502L))),
                eq(StockRestoreReason.ORDER_RESTORE));
    }

    @Test
    @DisplayName("대상 주문이 없으면 0을 돌려주고 잠금 조회·재고 복원을 하지 않는다")
    void cancel_none() {
        given(orderRepository.findIdsByProductIdsAndStatusIn(Set.of(STOPPED_PRODUCT_ID), PENDING)).willReturn(List.of());

        assertThat(useCase.cancel(Set.of(STOPPED_PRODUCT_ID), OrderCancelType.PRODUCT_STOPPED, NOW)).isZero();
        verify(orderRepository, never()).findAllByIdInForUpdate(any());
        verifyNoInteractions(productApi);
    }

    @Test
    @DisplayName("상품 ID가 비어 있으면 조회하지 않고 0을 돌려준다")
    void cancel_emptyProductIds() {
        assertThat(useCase.cancel(List.of(), OrderCancelType.PRODUCT_STOPPED, NOW)).isZero();
        verifyNoInteractions(orderRepository, productApi);
    }
}
