package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.OrderCancelResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderCancelOrderUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;

    OrderCancelOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        // 취소 + 재고 복원까지 실제 흐름으로 검증하려고 OrderCancelSupport는 실제 객체를 쓴다
        useCase = new OrderCancelOrderUseCase(orderRepository, new OrderCancelSupport(productApi));
    }

    private static final Long BUYER_ID = 3L;
    private static final Long ORDER_ID = 500L;
    private static final Long PRODUCT_ID = 10L;

    /** 장바구니 항목 1번(옵션 100, 10,000원 × 2)으로 방금 만든 주문 */
    private Order order(LocalDateTime createdAt) {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), createdAt, 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("결제 대기 주문을 취소하면 CANCELED가 되고, 주문 항목 전체로 ORDER_RESTORE 재고 복원을 요청한다")
    void cancel() {
        Order order = order(LocalDateTime.now());
        given(orderRepository.findProductIdsByOrderIdAndBuyerId(ORDER_ID, BUYER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        OrderCancelResponse response = useCase.cancel(BUYER_ID, ORDER_ID);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(response.canceledAt()).isNotNull();
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.USER);
        verify(productApi).restoreStock(
                argThat(items -> items.size() == 1
                        && items.get(0).orderId().equals(ORDER_ID)
                        && items.get(0).optionId().equals(100L)
                        && items.get(0).quantity() == 2),
                eq(StockRestoreReason.ORDER_RESTORE));
    }

    @Test
    @DisplayName("결제 완료 주문이면 409-ORDER_NOT_CANCELABLE이고 재고 복원을 요청하지 않는다")
    void paidOrder() {
        Order order = order(LocalDateTime.now());
        order.pay(LocalDateTime.now());
        given(orderRepository.findProductIdsByOrderIdAndBuyerId(ORDER_ID, BUYER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> useCase.cancel(BUYER_ID, ORDER_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CANCELABLE");
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("이미 취소된 주문이면 409-ORDER_NOT_CANCELABLE이고 재고 복원을 요청하지 않는다")
    void alreadyCanceled() {
        Order order = order(LocalDateTime.now());
        order.cancel(OrderCancelType.USER, "구매자 취소", LocalDateTime.now());
        given(orderRepository.findProductIdsByOrderIdAndBuyerId(ORDER_ID, BUYER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> useCase.cancel(BUYER_ID, ORDER_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CANCELABLE");
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("내 주문이 아니거나 없는 주문이면 404-ORDER_NOT_FOUND")
    void notFound() {
        given(orderRepository.findProductIdsByOrderIdAndBuyerId(ORDER_ID, BUYER_ID)).willReturn(List.of());

        assertThatThrownBy(() -> useCase.cancel(BUYER_ID, ORDER_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "404-ORDER_NOT_FOUND");
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("잠금 순서: 상품 ID 조회 → 상품 읽기 잠금 → 주문 쓰기 잠금 → 재고 복원 (상품 중지·삭제와 같은 순서)")
    void lockOrder() {
        Order order = order(LocalDateTime.now());
        given(orderRepository.findProductIdsByOrderIdAndBuyerId(ORDER_ID, BUYER_ID)).willReturn(List.of(PRODUCT_ID));
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));

        useCase.cancel(BUYER_ID, ORDER_ID);

        InOrder inOrder = inOrder(orderRepository, productApi);
        inOrder.verify(orderRepository).findProductIdsByOrderIdAndBuyerId(ORDER_ID, BUYER_ID);
        inOrder.verify(productApi).lockProductsForOrderChange(List.of(PRODUCT_ID));
        inOrder.verify(orderRepository).findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID);
        inOrder.verify(productApi).restoreStock(any(), eq(StockRestoreReason.ORDER_RESTORE));
    }

    @Test
    @DisplayName("내 주문이 아니거나 없는 주문이면 상품을 잠그지 않는다")
    void notFound_noLock() {
        given(orderRepository.findProductIdsByOrderIdAndBuyerId(ORDER_ID, BUYER_ID)).willReturn(List.of());

        assertThatThrownBy(() -> useCase.cancel(BUYER_ID, ORDER_ID)).isInstanceOf(DomainException.class);
        verify(productApi, never()).lockProductsForOrderChange(any());
        verify(orderRepository, never()).findByIdAndBuyerIdForUpdate(any(), any());
    }
}
