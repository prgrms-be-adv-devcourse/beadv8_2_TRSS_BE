package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderExpireOrdersUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;

    OrderExpireOrdersUseCase useCase;

    private static final Long ORDER_ID = 500L;
    private static final Long PRODUCT_ID = 10L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 15, 10, 0);
    private static final LocalDateTime EXPIRES_AT = CREATED_AT.plusMinutes(15);

    @BeforeEach
    void setUp() {
        // OrderPolicy는 값 객체라 실제로 만들고(만료 여유 1분), 재고 복원까지 보려고 OrderCancelSupport도 실제 객체를 쓴다
        useCase = new OrderExpireOrdersUseCase(orderRepository, new OrderPolicy(15, 7, 99, 100, 1),
                new OrderCancelSupport(productApi));
    }

    /** CREATED_AT에 만든 결제 대기 주문 (만료 시각 = CREATED_AT + 15분) */
    private Order pendingOrder() {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), CREATED_AT, 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("findTargetIds: 만료 시각이 지금 - 1분 이전인 결제 대기(CREATED, PAYMENT_FAILED) 주문을 afterId 다음부터 limit건 조회한다")
    void findTargetIds() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 11, 0);
        given(orderRepository.findExpireTargetIds(
                eq(List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED)), eq(now.minusMinutes(1)), eq(300L),
                argThat((Pageable p) -> p.getPageNumber() == 0 && p.getPageSize() == 100)))
                .willReturn(List.of(301L, 302L));

        assertThat(useCase.findTargetIds(now, 300L, 100)).containsExactly(301L, 302L);
    }

    @Test
    @DisplayName("expire: 만료 시각 + 1분이 지난 결제 대기 주문이면 CANCELED·EXPIRED로 취소하고 ORDER_RESTORE로 재고를 복원한다")
    void expire() {
        Order order = pendingOrder();
        LocalDateTime now = EXPIRES_AT.plusMinutes(1);
        given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        boolean result = useCase.expire(ORDER_ID, now);

        assertThat(result).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.EXPIRED);
        assertThat(order.getCanceledAt()).isEqualTo(now);
        verify(productApi).restoreStock(
                argThat(items -> items.size() == 1
                        && items.get(0).orderId().equals(ORDER_ID)
                        && items.get(0).optionId().equals(100L)
                        && items.get(0).quantity() == 2),
                eq(StockRestoreReason.ORDER_RESTORE));
    }

    @Test
    @DisplayName("expire: 조회 후 결제가 먼저 끝나 PAID면 건너뛰고 재고를 복원하지 않는다")
    void expire_paid() {
        Order order = pendingOrder();
        order.pay(EXPIRES_AT.minusSeconds(1));   // 만료 직전 결제
        given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        assertThat(useCase.expire(ORDER_ID, EXPIRES_AT.plusMinutes(1))).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("expire: 조회 후 구매자가 먼저 취소했으면 건너뛰고 재고를 다시 복원하지 않는다")
    void expire_alreadyCanceled() {
        Order order = pendingOrder();
        order.cancel(OrderCancelType.USER, "구매자 취소", EXPIRES_AT.plusSeconds(30));
        given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        assertThat(useCase.expire(ORDER_ID, EXPIRES_AT.plusMinutes(1))).isFalse();
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.USER);
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("expire: 만료 시각 + 1분이 아직 안 됐으면 건너뛴다")
    void expire_notYet() {
        Order order = pendingOrder();
        given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        assertThat(useCase.expire(ORDER_ID, EXPIRES_AT.plusMinutes(1).minusSeconds(1))).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("expire: 주문이 없으면 건너뛴다")
    void expire_notFound() {
        given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.empty());

        assertThat(useCase.expire(ORDER_ID, EXPIRES_AT.plusMinutes(1))).isFalse();
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("expire 잠금 순서: 상품 ID 조회 → 상품 읽기 잠금 → 주문 쓰기 잠금 → 재고 복원")
    void expire_lockOrder() {
        Order order = pendingOrder();
        given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        useCase.expire(ORDER_ID, EXPIRES_AT.plusMinutes(1));

        InOrder inOrder = inOrder(orderRepository, productApi);
        inOrder.verify(orderRepository).findProductIdsByOrderId(ORDER_ID);
        inOrder.verify(productApi).lockProductsForOrderChange(List.of(PRODUCT_ID));
        inOrder.verify(orderRepository).findByIdForUpdate(ORDER_ID);
        inOrder.verify(productApi).restoreStock(any(), eq(StockRestoreReason.ORDER_RESTORE));
    }

    @Test
    @DisplayName("expire: 주문 항목이 없으면(없는 주문) 상품·주문을 잠그지 않고 건너뛴다")
    void expire_noItems() {
        given(orderRepository.findProductIdsByOrderId(ORDER_ID)).willReturn(List.of());

        assertThat(useCase.expire(ORDER_ID, EXPIRES_AT.plusMinutes(1))).isFalse();
        verify(productApi, never()).lockProductsForOrderChange(any());
        verify(orderRepository, never()).findByIdForUpdate(any());
    }
}
