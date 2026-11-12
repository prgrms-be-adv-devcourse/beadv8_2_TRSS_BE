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
        order.requestReturn(ReturnReasonCode.CHANGE_OF_MIND, null, NOW.plusDays(1));   // 배송지 없는 주문은 PAID면 요청 가능

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
        returnRequested.requestReturn(ReturnReasonCode.DEFECTIVE, null, NOW.plusDays(1));
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

    // ===== 배송지·배송 완료 (PR 4) =====

    private static ShippingAddress address() {
        return ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞");
    }

    /** 배송지가 있는 주문을 NOW + 1분에 결제 */
    private Order paidOrderWithAddress() {
        Order order = Order.create(1L, null, List.of(spec(10L, 10_000, 1)), address(), NOW, EXPIRE_MINUTES);
        order.pay(NOW.plusMinutes(1));
        return order;
    }

    @Test
    @DisplayName("배송지와 함께 만든 주문은 배송지를 갖고, 배송지 없이 만든 주문은 갖지 않는다")
    void create_withShippingAddress() {
        Order withAddress = Order.create(1L, null, List.of(spec(10L, 10_000, 1)), address(), NOW, EXPIRE_MINUTES);
        Order withoutAddress = createOrder();

        assertThat(withAddress.hasShippingAddress()).isTrue();
        assertThat(withAddress.getShippingAddress().toDto().receiverPhone()).isEqualTo("01012345678");
        assertThat(withoutAddress.hasShippingAddress()).isFalse();
        assertThat(withoutAddress.getShippingAddress()).isNull();
    }

    @Test
    @DisplayName("배송지 컬럼이 모두 null이라 빈 객체로 읽힌 경우도 배송지 없는 주문이다")
    void hasShippingAddress_emptyEmbeddable() {
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "shippingAddress", ShippingAddress.of(null, null, null, null, null, null));

        assertThat(order.hasShippingAddress()).isFalse();
    }

    @Test
    @DisplayName("배송지가 있고 배송 완료 전이면 구매 확정은 409 ORDER_NOT_DELIVERED, 상태는 PAID 그대로")
    void confirm_withAddress_notDelivered() {
        Order order = paidOrderWithAddress();

        assertThatThrownBy(() -> order.confirm(ConfirmType.MANUAL, NOW.plusDays(1)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_DELIVERED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getConfirmedAt()).isNull();
    }

    @Test
    @DisplayName("배송지가 있고 배송 완료됐으면 구매 확정된다")
    void confirm_withAddress_delivered() {
        Order order = paidOrderWithAddress();
        order.markDelivered(NOW.plusDays(2));

        order.confirm(ConfirmType.MANUAL, NOW.plusDays(3));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(NOW.plusDays(3));
    }

    @Test
    @DisplayName("배송지 없는 주문(기존 주문)은 배송 완료 없이도 PAID면 지금처럼 확정된다")
    void confirm_withoutAddress() {
        Order order = createOrder();
        order.pay(NOW.plusMinutes(1));

        order.confirm(ConfirmType.MANUAL, NOW.plusMinutes(2));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("결제 전 주문은 배송지가 있어도 ORDER_NOT_CONFIRMABLE이 먼저다")
    void confirm_withAddress_notPaid() {
        Order order = Order.create(1L, null, List.of(spec(10L, 10_000, 1)), address(), NOW, EXPIRE_MINUTES);

        assertThatThrownBy(() -> order.confirm(ConfirmType.MANUAL, NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-ORDER_NOT_CONFIRMABLE");
    }

    @Test
    @DisplayName("배송지가 있는 주문의 자동 확정은 배송 완료 + 7일 기준, 배송 완료 전이면 결제 후 며칠이 지나도 대상이 아니다")
    void isAutoConfirmable_withAddress() {
        Order order = paidOrderWithAddress();
        assertThat(order.isAutoConfirmable(NOW.plusDays(30), 7)).isFalse();

        LocalDateTime deliveredAt = NOW.plusDays(3);
        order.markDelivered(deliveredAt);

        assertThat(order.isAutoConfirmable(deliveredAt.plusDays(7).minusSeconds(1), 7)).isFalse();
        assertThat(order.isAutoConfirmable(deliveredAt.plusDays(7), 7)).isTrue();
    }

    @Test
    @DisplayName("배송지 없는 주문의 자동 확정은 지금처럼 결제 + 7일 기준이다 (배송 완료 기록과 무관)")
    void isAutoConfirmable_withoutAddress() {
        Order order = createOrder();
        LocalDateTime paidAt = NOW.plusMinutes(1);
        order.pay(paidAt);

        assertThat(order.isAutoConfirmable(paidAt.plusDays(7), 7)).isTrue();
    }

    @Test
    @DisplayName("배송 완료가 두 번 기록돼도 처음 값을 유지한다")
    void markDelivered_twice_keepsFirst() {
        Order order = paidOrderWithAddress();
        LocalDateTime first = NOW.plusDays(2);

        order.markDelivered(first);
        order.markDelivered(NOW.plusDays(5));

        assertThat(order.getDeliveredAt()).isEqualTo(first);
    }

    // ===== 결제 중(PAYING) =====

    @Test
    @DisplayName("결제 시작: 결제 대기 주문은 PAYING이 되고 멱등키·시작 시각이 기록된다. 결제 실패 주문도 다시 시작할 수 있다")
    void startPaying() {
        Order order = createOrder();

        order.startPaying("K1", NOW.plusMinutes(1));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYING);
        assertThat(order.getPaymentIdempotencyKey()).isEqualTo("K1");
        assertThat(order.getPayingStartedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(order.isPayingWith("K1")).isTrue();
        assertThat(order.isPayingWith("K2")).isFalse();
        assertThat(order.isPaymentPending()).isFalse();

        order.failPayment("잔액 부족");
        order.startPaying("K2", NOW.plusMinutes(2));
        assertThat(order.isPayingWith("K2")).isTrue();
    }

    @Test
    @DisplayName("결제 시작: 결제 중이면 409-PAYMENT_IN_PROGRESS, 결제 완료면 409-ORDER_STATE_INVALID, 만료면 409-ORDER_EXPIRED")
    void startPaying_rejected() {
        Order paying = createOrder();
        paying.startPaying("K1", NOW.plusMinutes(1));
        assertThatThrownBy(() -> paying.startPaying("K2", NOW.plusMinutes(2)))
                .extracting("resultCode").isEqualTo("409-PAYMENT_IN_PROGRESS");

        Order paid = createOrder();
        paid.pay(NOW.plusMinutes(1));
        assertThatThrownBy(() -> paid.startPaying("K2", NOW.plusMinutes(2)))
                .extracting("resultCode").isEqualTo("409-ORDER_STATE_INVALID");

        Order expired = createOrder();
        assertThatThrownBy(() -> expired.startPaying("K1", NOW.plusMinutes(EXPIRE_MINUTES)))
                .extracting("resultCode").isEqualTo("409-ORDER_EXPIRED");
    }

    @Test
    @DisplayName("결제 성공 반영: PAYING → PAID. 만료 시각이 지난 뒤 반영돼도 PAID가 된다")
    void completePayment_afterExpiresAt() {
        Order order = createOrder();
        order.startPaying("K1", NOW.plusMinutes(14));

        order.completePayment(NOW.plusMinutes(20));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW.plusMinutes(20));
        assertThat(order.isPaidWith("K1")).isTrue();
        assertThat(order.getStatusHistories()).extracting(OrderStatusHistory::getToStatus)
                .containsExactly(OrderStatus.CREATED, OrderStatus.PAYING, OrderStatus.PAID);
    }

    @Test
    @DisplayName("결제 실패 반영: PAYING → PAYMENT_FAILED. 결제 중이 아닌 주문에 결과를 반영하면 코드 오류")
    void failPayment() {
        Order order = createOrder();
        order.startPaying("K1", NOW.plusMinutes(1));

        order.failPayment("잔액 부족");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.isPaymentPending()).isTrue();
        assertThatThrownBy(() -> order.completePayment(NOW.plusMinutes(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.failPayment("다시")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("결제 중 주문은 취소할 수 없고(409-PAYMENT_IN_PROGRESS) 만료 처리 대상도 아니다")
    void paying_notCancelableNorExpirable() {
        Order order = createOrder();
        order.startPaying("K1", NOW.plusMinutes(1));

        assertThatThrownBy(() -> order.cancel(OrderCancelType.USER, "구매자 취소", NOW.plusMinutes(2)))
                .extracting("resultCode").isEqualTo("409-PAYMENT_IN_PROGRESS");
        assertThat(order.isExpirable(NOW.plusMinutes(60), 1)).isFalse();
        assertThat(order.remainingPaymentSeconds(NOW.plusMinutes(2))).isZero();
    }

    @Test
    @DisplayName("상태 묶음: PAYING은 진행 중·결제 미완료에 들어가고 결제 대기에는 들어가지 않는다")
    void statusGroups() {
        assertThat(Order.ACTIVE_STATUSES).contains(OrderStatus.PAYING);
        assertThat(Order.PAYMENT_UNFINISHED_STATUSES).contains(OrderStatus.PAYING, OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED);
        assertThat(Order.PAYMENT_PENDING_STATUSES).doesNotContain(OrderStatus.PAYING);
    }
}
