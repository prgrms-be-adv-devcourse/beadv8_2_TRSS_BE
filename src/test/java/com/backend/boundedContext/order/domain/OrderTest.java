package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order 도메인 단위 테스트
 */
class OrderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 14, 0);
    private static final int EXPIRE_MINUTES = 15;

    @Test
    @DisplayName("주문을 만들면 CREATED 상태이고 만료 시각은 생성 + 15분이다")
    void create_success() {
        Order order = Order.create(1L, List.of(spec(10L, 10_000, 2), spec(20L, 5_000, 1)), NOW, EXPIRE_MINUTES);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getBuyerId()).isEqualTo(1L);
        assertThat(order.getExpiresAt()).isEqualTo(NOW.plusMinutes(15));
        assertThat(order.getOrderNumber()).startsWith("ORD-20261013-");
        assertThat(order.isPaymentPending()).isTrue();
    }

    @Test
    @DisplayName("주문 금액은 항목 금액(단가 x 수량)의 합이다")
    void create_totalPrice() {
        Order order = Order.create(1L, List.of(spec(10L, 10_000, 2), spec(20L, 5_000, 1)), NOW, EXPIRE_MINUTES);

        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getItems().get(0).getLineAmount()).isEqualTo(20_000);
        assertThat(order.getTotalPrice()).isEqualTo(25_000);
        assertThat(order.getItems()).allMatch(item -> item.getOrder() == order);
    }

    @Test
    @DisplayName("주문 항목이 없으면 ORDER_ITEM_EMPTY")
    void create_empty() {
        assertThatThrownBy(() -> Order.create(1L, List.of(), NOW, EXPIRE_MINUTES))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-ORDER_ITEM_EMPTY");
    }

    @Test
    @DisplayName("같은 옵션이 두 줄이면 ORDER_OPTION_DUPLICATED")
    void create_duplicateOption() {
        assertThatThrownBy(() -> Order.create(1L, List.of(spec(10L, 1_000, 1), spec(10L, 1_000, 2)), NOW, EXPIRE_MINUTES))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-ORDER_OPTION_DUPLICATED");
    }

    @Test
    @DisplayName("수량이 1 미만이면 ORDER_QUANTITY_INVALID")
    void create_quantityZero() {
        assertThatThrownBy(() -> Order.create(1L, List.of(spec(10L, 1_000, 0)), NOW, EXPIRE_MINUTES))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-ORDER_QUANTITY_INVALID");
    }

    private Order createOrder() {
        return Order.create(1L, List.of(spec(10L, 10_000, 1)), NOW, EXPIRE_MINUTES);
    }

    private OrderItemSpec spec(Long optionId, long unitPrice, int quantity) {
        return new OrderItemSpec(optionId + 1000, optionId + 100, optionId, 7L,
                "상품" + optionId, "기본", "테스트상점", unitPrice, quantity);
    }

    @Test
    @DisplayName("주문을 만들면 상태 이력 첫 줄(null → CREATED)이 남는다")
    void create_statusHistory() {
        Order order = Order.create(1L, List.of(spec(10L, 10_000, 1)), NOW, EXPIRE_MINUTES);

        assertThat(order.getStatusHistories()).hasSize(1);
        OrderStatusHistory history = order.getStatusHistories().get(0);
        assertThat(history.getOrder()).isSameAs(order);
        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(history.getReason()).isNull();
    }

    @Test
    @DisplayName("결제 대기 주문을 취소하면 CANCELED가 되고 취소 정보와 이력이 남는다")
    void cancel_pending() {
        Order order = createOrder();
        LocalDateTime now = NOW.plusMinutes(1);

        order.cancel(OrderCancelType.USER, "구매자 취소", now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.USER);
        assertThat(order.getCanceledAt()).isEqualTo(now);
        assertThat(order.getStatusHistories()).last()
                .extracting(OrderStatusHistory::getToStatus).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("결제 완료 주문은 취소할 수 없다")
    void cancel_paid_fail() {
        Order order = createOrder();
        order.pay(NOW.plusMinutes(1));

        assertThatThrownBy(() -> order.cancel(OrderCancelType.USER, "구매자 취소", NOW.plusMinutes(1)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CANCELABLE");
    }

    @Test
    @DisplayName("이미 취소된 주문은 다시 취소할 수 없다")
    void cancel_twice_fail() {
        Order order = createOrder();
        order.cancel(OrderCancelType.USER, "구매자 취소", NOW.plusMinutes(1));

        assertThatThrownBy(() -> order.cancel(OrderCancelType.USER, "구매자 취소", NOW.plusMinutes(1)))
                .isInstanceOf(DomainException.class);
    }
}