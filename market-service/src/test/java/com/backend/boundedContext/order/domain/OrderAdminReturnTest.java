package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 관리자 반품 승인·거절 도메인 규칙 (Order.approveReturn, Order.rejectReturn)
 */
class OrderAdminReturnTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final LocalDateTime PAID_AT = NOW.plusMinutes(1);
    private static final LocalDateTime DELIVERED_AT = NOW.plusDays(2);
    private static final LocalDateTime REQUESTED_AT = DELIVERED_AT.plusDays(1);
    private static final LocalDateTime DECIDED_AT = DELIVERED_AT.plusDays(2);
    private static final long ITEM_1 = 101L;
    private static final long ITEM_2 = 102L;

    // ---------- 승인 ----------

    @Test
    @DisplayName("승인: RETURNED, 승인 시각, 항목별 재고 복원 여부 저장, 이력 RETURN_REQUESTED → RETURNED 1행")
    void approve_success() {
        Order order = returnRequestedOrder();
        int historiesBefore = order.getStatusHistories().size();

        order.approveReturn(List.of(decision(ITEM_1, true), decision(ITEM_2, false)), DECIDED_AT);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURNED);
        assertThat(order.getReturnedAt()).isEqualTo(DECIDED_AT);
        assertThat(order.getItems()).extracting(OrderItem::getId, OrderItem::getRestoreStock)
                .containsExactly(tuple(ITEM_1, true), tuple(ITEM_2, false));
        assertThat(order.getStatusHistories()).hasSize(historiesBefore + 1);
        OrderStatusHistory last = order.getStatusHistories().getLast();
        assertThat(last.getFromStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(last.getToStatus()).isEqualTo(OrderStatus.RETURNED);
        // 반품 승인 주문은 구매 확정되지 않는다(정산 후보 없음)
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.isAutoConfirmable(DELIVERED_AT.plusDays(30), 7)).isFalse();
    }

    @Test
    @DisplayName("승인: 보낸 순서가 항목 순서와 달라도 id로 맞춘다")
    void approve_anyOrder() {
        Order order = returnRequestedOrder();

        order.approveReturn(List.of(decision(ITEM_2, true), decision(ITEM_1, false)), DECIDED_AT);

        assertThat(order.getItems()).extracting(OrderItem::getRestoreStock).containsExactly(false, true);
    }

    @Test
    @DisplayName("승인: 항목이 빠지면 400 RETURN_ITEMS_MISMATCH, 상태·복원 여부는 그대로")
    void approve_missingItem() {
        Order order = returnRequestedOrder();

        assertFails(() -> order.approveReturn(List.of(decision(ITEM_1, true)), DECIDED_AT), "400-RETURN_ITEMS_MISMATCH");
        assertUnchanged(order);
    }

    @Test
    @DisplayName("승인: 같은 항목을 두 번 보내면(값이 같아도) 400 RETURN_ITEMS_MISMATCH")
    void approve_duplicateItem() {
        Order order = returnRequestedOrder();

        assertFails(() -> order.approveReturn(
                List.of(decision(ITEM_1, true), decision(ITEM_2, false), decision(ITEM_1, true)), DECIDED_AT),
                "400-RETURN_ITEMS_MISMATCH");
        assertUnchanged(order);
    }

    @Test
    @DisplayName("승인: 다른 주문의 항목 id가 섞이면 400 RETURN_ITEMS_MISMATCH")
    void approve_foreignItem() {
        Order order = returnRequestedOrder();

        assertFails(() -> order.approveReturn(List.of(decision(ITEM_1, true), decision(999L, false)), DECIDED_AT),
                "400-RETURN_ITEMS_MISMATCH");
        assertFails(() -> order.approveReturn(
                List.of(decision(ITEM_1, true), decision(ITEM_2, true), decision(999L, false)), DECIDED_AT),
                "400-RETURN_ITEMS_MISMATCH");
        assertUnchanged(order);
    }

    @Test
    @DisplayName("승인: 반품 요청 중이 아니면(PAID·이미 승인·이미 거절) 409 ORDER_STATE_INVALID — 항목 검사보다 먼저")
    void approve_notRequested() {
        Order paid = deliveredOrder();
        assertFails(() -> paid.approveReturn(List.of(), DECIDED_AT), "409-ORDER_STATE_INVALID");

        Order returned = returnRequestedOrder();
        returned.approveReturn(bothItems(false), DECIDED_AT);
        assertFails(() -> returned.approveReturn(bothItems(true), DECIDED_AT.plusHours(1)), "409-ORDER_STATE_INVALID");
        assertThat(returned.getItems()).extracting(OrderItem::getRestoreStock).containsOnly(false);   // 첫 결정 유지

        Order rejected = returnRequestedOrder();
        rejected.rejectReturn("사용 흔적", DECIDED_AT);
        assertFails(() -> rejected.approveReturn(bothItems(true), DECIDED_AT.plusHours(1)), "409-ORDER_STATE_INVALID");
    }

    @Test
    @DisplayName("반품 승인 주문은 진행 중 주문(ACTIVE_STATUSES)이 아니다 — 회원 탈퇴·판매자 철회를 막지 않는다")
    void approve_notActive() {
        Order order = returnRequestedOrder();
        order.approveReturn(bothItems(false), DECIDED_AT);

        assertThat(Order.ACTIVE_STATUSES).doesNotContain(order.getStatus());
    }

    // ---------- 거절 ----------

    @Test
    @DisplayName("거절: PAID로 돌아오고 거절 시각·사유 저장, 요청 정보는 그대로, 이력 RETURN_REQUESTED → PAID(사유)")
    void reject_success() {
        Order order = returnRequestedOrder();

        order.rejectReturn("사용 흔적이 있어 반품할 수 없습니다", DECIDED_AT);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getReturnRejectedAt()).isEqualTo(DECIDED_AT);
        assertThat(order.getReturnRejectReason()).isEqualTo("사용 흔적이 있어 반품할 수 없습니다");
        assertThat(order.getReturnRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(order.getReturnReasonCode()).isEqualTo(ReturnReasonCode.DEFECTIVE);
        assertThat(order.getReturnedAt()).isNull();
        OrderStatusHistory last = order.getStatusHistories().getLast();
        assertThat(last.getFromStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(last.getToStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(last.getReason()).isEqualTo("사용 흔적이 있어 반품할 수 없습니다");
    }

    @Test
    @DisplayName("거절 후 자동 확정 기준일은 배송 완료 + 7일 그대로다(거절로 늘어나지 않음)")
    void reject_keepsAutoConfirmBase() {
        Order order = returnRequestedOrder();
        order.rejectReturn("사용 흔적", DECIDED_AT);

        assertThat(order.isAutoConfirmable(DELIVERED_AT.plusDays(7).minusSeconds(1), 7)).isFalse();
        assertThat(order.isAutoConfirmable(DELIVERED_AT.plusDays(7), 7)).isTrue();
    }

    @Test
    @DisplayName("배송지 없는 주문은 거절 후 결제 + 7일이 기준이다")
    void reject_noShippingAddress_basePaidAt() {
        Order order = Order.create(1L, List.of(spec(10L), spec(11L)), NOW, 15);
        order.pay(PAID_AT);
        order.requestReturn(ReturnReasonCode.CHANGE_OF_MIND, null, PAID_AT.plusDays(1));
        order.rejectReturn("오배송 아님", PAID_AT.plusDays(2));

        assertThat(order.isAutoConfirmable(PAID_AT.plusDays(7).minusSeconds(1), 7)).isFalse();
        assertThat(order.isAutoConfirmable(PAID_AT.plusDays(7), 7)).isTrue();
    }

    @Test
    @DisplayName("거절: 반품 요청 중이 아니면(PAID·이미 승인·이미 거절) 409 ORDER_STATE_INVALID")
    void reject_notRequested() {
        Order paid = deliveredOrder();
        assertFails(() -> paid.rejectReturn("사유", DECIDED_AT), "409-ORDER_STATE_INVALID");

        Order returned = returnRequestedOrder();
        returned.approveReturn(bothItems(false), DECIDED_AT);
        assertFails(() -> returned.rejectReturn("사유", DECIDED_AT), "409-ORDER_STATE_INVALID");
        assertThat(returned.getStatus()).isEqualTo(OrderStatus.RETURNED);

        Order rejected = returnRequestedOrder();
        rejected.rejectReturn("첫 사유", DECIDED_AT);
        assertFails(() -> rejected.rejectReturn("두 번째 사유", DECIDED_AT.plusHours(1)), "409-ORDER_STATE_INVALID");
        assertThat(rejected.getReturnRejectReason()).isEqualTo("첫 사유");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("거절 사유가 비면 코드 오류(요청 검증이 먼저 막는다), 상태는 그대로")
    void reject_blankReason(String reason) {
        Order order = returnRequestedOrder();

        assertThatThrownBy(() -> order.rejectReturn(reason, DECIDED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.getReturnRejectedAt()).isNull();
    }

    // ---------- 준비 ----------

    private static Order deliveredOrder() {
        Order order = Order.create(1L, null, List.of(spec(10L), spec(11L)),
                ShippingAddress.of("김수령", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", null), NOW, 15);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", ITEM_1);   // 저장 전이라 항목 id를 붙인다
        ReflectionTestUtils.setField(order.getItems().get(1), "id", ITEM_2);
        order.pay(PAID_AT);
        order.markDelivered(DELIVERED_AT);
        return order;
    }

    private static Order returnRequestedOrder() {
        Order order = deliveredOrder();
        order.requestReturn(ReturnReasonCode.DEFECTIVE, "깨짐", REQUESTED_AT);
        return order;
    }

    private static OrderItemSpec spec(long optionId) {
        return new OrderItemSpec(null, optionId, optionId, 2L, "상품" + optionId, "기본", "상점", 10_000, 2);
    }

    private static ReturnItemDecision decision(long orderItemId, boolean restore) {
        return new ReturnItemDecision(orderItemId, restore);
    }

    private static List<ReturnItemDecision> bothItems(boolean restore) {
        return List.of(decision(ITEM_1, restore), decision(ITEM_2, restore));
    }

    private static void assertUnchanged(Order order) {
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.getReturnedAt()).isNull();
        assertThat(order.getItems()).extracting(OrderItem::getRestoreStock).containsOnlyNulls();
    }

    private static void assertFails(Runnable action, String resultCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(resultCode);
    }
}
