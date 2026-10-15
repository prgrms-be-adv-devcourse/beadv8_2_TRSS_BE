package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCancelSupportTest {

    @Mock ProductApi productApi;
    @InjectMocks
    OrderCancelSupport cancelSupport;

    private static final Long ORDER_ID = 500L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 0);

    /** 옵션 100(× 2), 옵션 200(× 1) 두 줄짜리 결제 대기 주문 */
    private Order order() {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점A", 10_000, 2),
                new OrderItemSpec(2L, 20L, 200L, 7L, "틴트", "기본", "상점B", 5_000, 1)), NOW, 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", 1001L);
        ReflectionTestUtils.setField(order.getItems().get(1), "id", 1002L);
        return order;
    }

    @Test
    @DisplayName("결제 대기 주문을 취소하면 CANCELED가 되고, 주문 항목 전체를 ORDER_RESTORE로 재고 복원 요청한다")
    @SuppressWarnings("unchecked")
    void cancel() {
        Order order = order();
        LocalDateTime now = NOW.plusMinutes(16);

        cancelSupport.cancel(order, OrderCancelType.EXPIRED, "결제 시간 만료", now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.EXPIRED);
        assertThat(order.getCanceledAt()).isEqualTo(now);

        ArgumentCaptor<List<StockChangeDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(productApi).restoreStock(captor.capture(), eq(StockRestoreReason.ORDER_RESTORE));
        assertThat(captor.getValue())
                .extracting(StockChangeDto::orderItemId, StockChangeDto::optionId, StockChangeDto::quantity)
                .containsExactly(
                        tuple(1001L, 100L, 2),
                        tuple(1002L, 200L, 1));
    }

    @Test
    @DisplayName("결제 완료 주문이면 409-ORDER_NOT_CANCELABLE이고 재고 복원을 요청하지 않는다")
    void cancel_paid() {
        Order order = order();
        order.pay(NOW.plusMinutes(1));

        assertThatThrownBy(() -> cancelSupport.cancel(order, OrderCancelType.EXPIRED, "결제 시간 만료", NOW.plusMinutes(16)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CANCELABLE");
        verifyNoInteractions(productApi);
    }

    @Test
    @DisplayName("재고 복원이 실패하면 예외를 그대로 던져 호출한 쪽 트랜잭션이 취소까지 함께 롤백되게 한다")
    void cancel_restoreFails() {
        Order order = order();
        willThrow(new IllegalStateException("재고 복원 실패"))
                .given(productApi).restoreStock(anyList(), eq(StockRestoreReason.ORDER_RESTORE));

        assertThatThrownBy(() -> cancelSupport.cancel(order, OrderCancelType.EXPIRED, "결제 시간 만료", NOW.plusMinutes(16)))
                .isInstanceOf(IllegalStateException.class);
    }
}
