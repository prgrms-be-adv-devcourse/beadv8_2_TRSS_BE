package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    @DisplayName("결제 완료 주문을 구매 확정하면 CONFIRMED가 되고 확정 정보와 이력이 남는다")
    void confirm_paid() {
        Order order = createOrder();
        order.pay(NOW.plusMinutes(1));
        LocalDateTime now = NOW.plusDays(1);

        order.confirm(ConfirmType.MANUAL, now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmType()).isEqualTo(ConfirmType.MANUAL);
        assertThat(order.getConfirmedAt()).isEqualTo(now);
        assertThat(order.getStatusHistories()).last()
                .satisfies(history -> {
                    assertThat(history.getFromStatus()).isEqualTo(OrderStatus.PAID);
                    assertThat(history.getToStatus()).isEqualTo(OrderStatus.CONFIRMED);
                });
    }

    @Test
    @DisplayName("결제 전 주문은 구매 확정할 수 없다")
    void confirm_pending_fail() {
        Order order = createOrder();

        assertThatThrownBy(() -> order.confirm(ConfirmType.MANUAL, NOW.plusMinutes(1)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CONFIRMABLE");
    }

    @Test
    @DisplayName("반품 요청 중인 주문은 구매 확정할 수 없다")
    void confirm_returnRequested_fail() {
        Order order = createOrder();
        order.pay(NOW.plusMinutes(1));
        ReflectionTestUtils.setField(order, "status", OrderStatus.RETURN_REQUESTED);   // 반품 요청 기능 추가 전이라 상태만 바꿈

        assertThatThrownBy(() -> order.confirm(ConfirmType.MANUAL, NOW.plusDays(1)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CONFIRMABLE");
    }

    @Test
    @DisplayName("이미 구매 확정된 주문은 다시 확정할 수 없다")
    void confirm_twice_fail() {
        Order order = createOrder();
        order.pay(NOW.plusMinutes(1));
        order.confirm(ConfirmType.MANUAL, NOW.plusDays(1));

        assertThatThrownBy(() -> order.confirm(ConfirmType.AUTO, NOW.plusDays(8)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CONFIRMABLE");
    }

    @Test
    @DisplayName("결제 완료 후 7일이 되는 시각부터 자동 확정 대상이다")
    void isAutoConfirmable_boundary() {
        Order order = createOrder();
        LocalDateTime paidAt = NOW.plusMinutes(1);
        order.pay(paidAt);

        assertThat(order.isAutoConfirmable(paidAt.plusDays(7).minusSeconds(1), 7)).isFalse();
        assertThat(order.isAutoConfirmable(paidAt.plusDays(7), 7)).isTrue();
        assertThat(order.isAutoConfirmable(paidAt.plusDays(10), 7)).isTrue();
    }

    @Test
    @DisplayName("결제 전·반품 요청 중인 주문은 7일이 지나도 자동 확정 대상이 아니다")
    void isAutoConfirmable_notPaid() {
        Order pending = createOrder();
        assertThat(pending.isAutoConfirmable(NOW.plusDays(30), 7)).isFalse();

        Order returnRequested = createOrder();
        returnRequested.pay(NOW.plusMinutes(1));
        ReflectionTestUtils.setField(returnRequested, "status", OrderStatus.RETURN_REQUESTED);
        assertThat(returnRequested.isAutoConfirmable(NOW.plusDays(30), 7)).isFalse();
    }

    @Test
    @DisplayName("결제 만료 시각 + 1분이 되는 시각부터 만료 처리 대상이다")
    void isExpirable_boundary() {
        Order order = createOrder();   // 만료 시각 = NOW + 15분
        LocalDateTime expiresAt = NOW.plusMinutes(EXPIRE_MINUTES);

        assertThat(order.isExpirable(expiresAt, 1)).isFalse();
        assertThat(order.isExpirable(expiresAt.plusMinutes(1).minusSeconds(1), 1)).isFalse();
        assertThat(order.isExpirable(expiresAt.plusMinutes(1), 1)).isTrue();
    }

    @Test
    @DisplayName("결제 실패(PAYMENT_FAILED) 주문도 만료 시각 + 1분이 지나면 만료 처리 대상이다")
    void isExpirable_paymentFailed() {
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAYMENT_FAILED);   // 결제 실패 처리 메서드가 없어 상태만 바꿈

        assertThat(order.isExpirable(NOW.plusMinutes(EXPIRE_MINUTES + 1), 1)).isTrue();
    }

    @Test
    @DisplayName("결제 완료·취소된 주문은 만료 시각이 지나도 만료 처리 대상이 아니다")
    void isExpirable_notPending() {
        LocalDateTime later = NOW.plusDays(1);

        Order paid = createOrder();
        paid.pay(NOW.plusMinutes(1));
        assertThat(paid.isExpirable(later, 1)).isFalse();

        Order canceled = createOrder();
        canceled.cancel(OrderCancelType.USER, "구매자 취소", NOW.plusMinutes(1));
        assertThat(canceled.isExpirable(later, 1)).isFalse();
    }

    @Test
    @DisplayName("만료 처리로 취소하면 CANCELED·EXPIRED가 되고 취소 사유가 이력에 남는다")
    void cancel_expired() {
        Order order = createOrder();
        LocalDateTime now = NOW.plusMinutes(EXPIRE_MINUTES + 1);

        order.cancel(OrderCancelType.EXPIRED, "결제 시간 만료", now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.EXPIRED);
        assertThat(order.getCanceledAt()).isEqualTo(now);
        assertThat(order.getStatusHistories()).last()
                .extracting(OrderStatusHistory::getReason).isEqualTo("결제 시간 만료");
    }
}
