package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 반품 요청 도메인 규칙 (Order.requestReturn)
 * 검사 순서: 이미 요청함 → PAID 아님 → 배송 완료 전 → OTHER 상세 없음
 */
class OrderReturnTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final LocalDateTime PAID_AT = NOW.plusMinutes(1);
    private static final LocalDateTime DELIVERED_AT = NOW.plusDays(2);

    // ---------- 성공 ----------

    @Test
    @DisplayName("PAID + 배송 완료면 RETURN_REQUESTED가 되고 요청 시각·사유가 저장되며 이력이 1행(PAID → RETURN_REQUESTED) 늘어난다")
    void request_success() {
        Order order = deliveredOrder();
        int historiesBefore = order.getStatusHistories().size();
        LocalDateTime requestedAt = DELIVERED_AT.plusDays(1);

        order.requestReturn(ReturnReasonCode.DEFECTIVE, "포장이 찢어져 왔어요", requestedAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.getReturnRequestedAt()).isEqualTo(requestedAt);
        assertThat(order.getReturnReasonCode()).isEqualTo(ReturnReasonCode.DEFECTIVE);
        assertThat(order.getReturnReason()).isEqualTo("포장이 찢어져 왔어요");
        assertThat(order.getStatusHistories()).hasSize(historiesBefore + 1);
        OrderStatusHistory last = order.getStatusHistories().get(order.getStatusHistories().size() - 1);
        assertThat(last.getFromStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(last.getToStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(last.getReason()).isEqualTo("DEFECTIVE");   // 이력에는 코드만, 자유 입력 사유는 넣지 않는다
    }

    @Test
    @DisplayName("배송 완료 7일이 지났어도 아직 PAID(자동 확정 전)면 요청할 수 있다 — 기한은 구매 확정 전까지")
    void request_afterSevenDays_stillPaid() {
        Order order = deliveredOrder();
        assertThat(order.isAutoConfirmable(DELIVERED_AT.plusDays(10), 7)).isTrue();   // 자동 확정 대상이지만 아직 확정 안 됨

        order.requestReturn(ReturnReasonCode.CHANGE_OF_MIND, null, DELIVERED_AT.plusDays(10));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
    }

    @Test
    @DisplayName("배송지 없는 주문(배송 흐름 이전 경로)은 PAID면 배송 완료 없이도 요청할 수 있다")
    void request_noShippingAddress() {
        Order order = Order.create(1L, List.of(spec()), NOW, 15);
        order.pay(PAID_AT);

        order.requestReturn(ReturnReasonCode.WRONG_ITEM, null, PAID_AT.plusDays(1));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
    }

    @Test
    @DisplayName("OTHER는 상세 사유가 있으면 요청된다")
    void request_other_withReason() {
        Order order = deliveredOrder();

        order.requestReturn(ReturnReasonCode.OTHER, "선물용인데 받는 분이 이미 갖고 있어요", DELIVERED_AT.plusDays(1));

        assertThat(order.getReturnReasonCode()).isEqualTo(ReturnReasonCode.OTHER);
        assertThat(order.getReturnReason()).isEqualTo("선물용인데 받는 분이 이미 갖고 있어요");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("OTHER가 아닌 코드는 상세 사유가 없어도 되고, 빈 사유는 null로 저장한다")
    void request_nonOther_blankReason(String reason) {
        Order order = deliveredOrder();

        order.requestReturn(ReturnReasonCode.NOT_AS_DESCRIBED, reason, DELIVERED_AT.plusDays(1));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.getReturnReason()).isNull();
    }

    // ---------- 실패 ----------

    @Test
    @DisplayName("두 번째 요청은 409 RETURN_ALREADY_REQUESTED")
    void request_twice() {
        Order order = deliveredOrder();
        order.requestReturn(ReturnReasonCode.DEFECTIVE, null, DELIVERED_AT.plusDays(1));

        assertFails(() -> order.requestReturn(ReturnReasonCode.DEFECTIVE, null, DELIVERED_AT.plusDays(2)),
                "409-RETURN_ALREADY_REQUESTED");
    }

    @Test
    @DisplayName("거절돼 PAID로 돌아온 뒤 다시 요청해도 409 RETURN_ALREADY_REQUESTED (주문당 1회)")
    void request_afterRejected() {
        Order order = deliveredOrder();
        order.requestReturn(ReturnReasonCode.DEFECTIVE, null, DELIVERED_AT.plusDays(1));
        // 거절(rejectReturn)은 #333 범위라 거절 후 상태만 만든다: PAID + returnRejectedAt, returnRequestedAt은 그대로
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        ReflectionTestUtils.setField(order, "returnRejectedAt", DELIVERED_AT.plusDays(2));

        assertFails(() -> order.requestReturn(ReturnReasonCode.CHANGE_OF_MIND, null, DELIVERED_AT.plusDays(3)),
                "409-RETURN_ALREADY_REQUESTED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("배송지가 있는데 배송 완료 전이면 409 ORDER_NOT_DELIVERED, 상태·이력은 그대로")
    void request_notDelivered() {
        Order order = paidOrderWithAddress();
        int historiesBefore = order.getStatusHistories().size();

        assertFails(() -> order.requestReturn(ReturnReasonCode.DEFECTIVE, null, PAID_AT.plusDays(1)),
                "409-ORDER_NOT_DELIVERED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getReturnRequestedAt()).isNull();
        assertThat(order.getStatusHistories()).hasSize(historiesBefore);
    }

    @Test
    @DisplayName("결제 전(CREATED)이면 409 ORDER_STATE_INVALID")
    void request_created() {
        Order order = Order.create(1L, null, List.of(spec()), address(), NOW, 15);

        assertFails(() -> order.requestReturn(ReturnReasonCode.DEFECTIVE, null, NOW.plusMinutes(1)), "409-ORDER_STATE_INVALID");
    }

    @Test
    @DisplayName("결제 중(PAYING)이면 409 ORDER_STATE_INVALID")
    void request_paying() {
        Order order = Order.create(1L, null, List.of(spec()), address(), NOW, 15);
        order.startPaying("K1", NOW.plusMinutes(1));

        assertFails(() -> order.requestReturn(ReturnReasonCode.DEFECTIVE, null, NOW.plusMinutes(2)), "409-ORDER_STATE_INVALID");
    }

    @Test
    @DisplayName("결제 실패(PAYMENT_FAILED)면 409 ORDER_STATE_INVALID")
    void request_paymentFailed() {
        Order order = Order.create(1L, null, List.of(spec()), address(), NOW, 15);
        order.startPaying("K1", NOW.plusMinutes(1));
        order.failPayment("잔액 부족");

        assertFails(() -> order.requestReturn(ReturnReasonCode.DEFECTIVE, null, NOW.plusMinutes(2)), "409-ORDER_STATE_INVALID");
    }

    @Test
    @DisplayName("취소(CANCELED)면 409 ORDER_STATE_INVALID")
    void request_canceled() {
        Order order = Order.create(1L, null, List.of(spec()), address(), NOW, 15);
        order.cancel(OrderCancelType.USER, "구매자 취소", NOW.plusMinutes(1));

        assertFails(() -> order.requestReturn(ReturnReasonCode.DEFECTIVE, null, NOW.plusMinutes(2)), "409-ORDER_STATE_INVALID");
    }

    @Test
    @DisplayName("구매 확정(CONFIRMED)되면 409 ORDER_STATE_INVALID")
    void request_confirmed() {
        Order order = deliveredOrder();
        order.confirm(ConfirmType.AUTO, DELIVERED_AT.plusDays(7));

        assertFails(() -> order.requestReturn(ReturnReasonCode.DEFECTIVE, null, DELIVERED_AT.plusDays(7)), "409-ORDER_STATE_INVALID");
        assertThat(order.getReturnRequestedAt()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("OTHER인데 상세 사유가 없거나 공백이면 400 RETURN_REASON_REQUIRED, 상태는 PAID 그대로")
    void request_other_blankReason(String reason) {
        Order order = deliveredOrder();

        assertFails(() -> order.requestReturn(ReturnReasonCode.OTHER, reason, DELIVERED_AT.plusDays(1)), "400-RETURN_REASON_REQUIRED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getReturnRequestedAt()).isNull();
    }

    // ---------- 검사 순서 ----------

    @Test
    @DisplayName("검사 순서: 이미 요청한 주문은 상태와 관계없이 RETURN_ALREADY_REQUESTED가 먼저다")
    void order_alreadyRequestedFirst() {
        Order order = deliveredOrder();
        order.requestReturn(ReturnReasonCode.DEFECTIVE, null, DELIVERED_AT.plusDays(1));   // 상태 RETURN_REQUESTED(PAID 아님)

        assertFails(() -> order.requestReturn(ReturnReasonCode.OTHER, null, DELIVERED_AT.plusDays(2)), "409-RETURN_ALREADY_REQUESTED");
    }

    @Test
    @DisplayName("검사 순서: 결제 중 + 미배송이면 ORDER_STATE_INVALID, 미배송 + OTHER 빈 사유면 ORDER_NOT_DELIVERED")
    void order_stateThenDeliveryThenReason() {
        Order paying = Order.create(1L, null, List.of(spec()), address(), NOW, 15);
        paying.startPaying("K1", NOW.plusMinutes(1));
        assertFails(() -> paying.requestReturn(ReturnReasonCode.OTHER, null, NOW.plusMinutes(2)), "409-ORDER_STATE_INVALID");

        Order notDelivered = paidOrderWithAddress();
        assertFails(() -> notDelivered.requestReturn(ReturnReasonCode.OTHER, null, PAID_AT.plusDays(1)), "409-ORDER_NOT_DELIVERED");
    }

    // ---------- 요청 뒤 다른 흐름 ----------

    @Test
    @DisplayName("반품 요청 중인 주문은 자동 확정 대상이 아니고 수동 확정도 409 ORDER_NOT_CONFIRMABLE")
    void afterRequest_notConfirmable() {
        Order order = deliveredOrder();
        order.requestReturn(ReturnReasonCode.DEFECTIVE, null, DELIVERED_AT.plusDays(1));

        assertThat(order.isAutoConfirmable(DELIVERED_AT.plusDays(30), 7)).isFalse();
        assertFails(() -> order.confirm(ConfirmType.MANUAL, DELIVERED_AT.plusDays(2)), "409-ORDER_NOT_CONFIRMABLE");
    }

    @Test
    @DisplayName("회원 탈퇴·판매자 철회 검사: 진행 중 상태에 RETURN_REQUESTED는 들어가고 RETURNED는 들어가지 않는다")
    void activeStatuses_includeReturnRequested() {
        assertThat(Order.ACTIVE_STATUSES).contains(OrderStatus.RETURN_REQUESTED).doesNotContain(OrderStatus.RETURNED);
    }

    @ParameterizedTest
    @EnumSource(ReturnReasonCode.class)
    @DisplayName("사유 코드 이름은 컬럼 길이(varchar 30) 안이다")
    void reasonCode_fitsColumn(ReturnReasonCode code) {
        assertThat(code.name().length()).isLessThanOrEqualTo(30);
    }

    // ---------- 도우미 ----------

    private static void assertFails(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String resultCode) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", resultCode);
    }

    private static ShippingAddress address() {
        return ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞");
    }

    private static OrderItemSpec spec() {
        return new OrderItemSpec(1010L, 110L, 10L, 7L, "상품10", "기본", "테스트상점", 10_000, 1);
    }

    /** 배송지 있는 주문을 PAID_AT에 결제 */
    private static Order paidOrderWithAddress() {
        Order order = Order.create(1L, null, List.of(spec()), address(), NOW, 15);
        order.pay(PAID_AT);
        return order;
    }

    /** 배송지 있는 주문을 결제하고 DELIVERED_AT에 배송 완료 */
    private static Order deliveredOrder() {
        Order order = paidOrderWithAddress();
        order.markDelivered(DELIVERED_AT);
        return order;
    }
}
