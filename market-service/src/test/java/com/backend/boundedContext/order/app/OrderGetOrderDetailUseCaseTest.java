package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.in.dto.OrderPaymentResponse;
import com.backend.boundedContext.order.in.dto.RefundStatus;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.delivery.out.DeliveryApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.out.PaymentApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrderGetOrderDetailUseCaseTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    PaymentApi paymentApi;
    @Mock
    DeliveryApi deliveryApi;
    @InjectMocks
    OrderGetOrderDetailUseCase useCase;

    private static final Long BUYER_ID = 3L;

    private Order order(Long orderId) {
        List<OrderItemSpec> specs = List.of(
                new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2),
                new OrderItemSpec(null, 11L, 101L, 2L, "블러셔", "기본", "상점", 5_000, 1));
        Order order = Order.create(BUYER_ID, specs, LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }

    @Test
    @DisplayName("본인 주문이면 항목 스냅샷과 금액을 담아 반환한다")
    void detail() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.of(order(500L)));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.orderId()).isEqualTo(500L);
        assertThat(response.totalPrice()).isEqualTo(25_000);
        assertThat(response.items()).extracting(OrderItemResponse::productName)
                .containsExactly("립스틱", "블러셔");
        assertThat(response.remainingSeconds()).isPositive();
        assertThat(response.payments()).isEmpty();   // 결제 시도가 없으면 빈 목록
        assertThat(response.shippingAddress()).isNull();
        assertThat(response.delivery()).isNull();
        assertThat(response.deliveryUnavailable()).isFalse();
        verifyNoInteractions(deliveryApi);   // 배송지 없는 주문은 delivery를 부르지 않는다
        // 반품하지 않은 주문: 반품 필드는 모두 null, 환불 상태 NONE
        assertThat(response.returnRequestedAt()).isNull();
        assertThat(response.returnReasonCode()).isNull();
        assertThat(response.returnReason()).isNull();
        assertThat(response.returnRejectedAt()).isNull();
        assertThat(response.returnRejectReason()).isNull();
        assertThat(response.returnedAt()).isNull();
        assertThat(response.refundStatus()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    @DisplayName("결제 내역은 결제 쪽이 준 순서(오래된 순) 그대로, 실패한 시도까지 담는다")
    void payments() {
        LocalDateTime failedAt = LocalDateTime.of(2026, 10, 18, 14, 3);
        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 18, 14, 6);
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.of(order(500L)));
        given(paymentApi.getPaymentAttempts(500L)).willReturn(List.of(
                new PaymentAttemptDto(1L, PaymentAttemptStatus.FAILED_INSUFFICIENT_BALANCE, 25_000, failedAt, null, null),
                new PaymentAttemptDto(2L, PaymentAttemptStatus.PAID, 25_000, paidAt, paidAt, null)));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.payments()).extracting(OrderPaymentResponse::paymentId, OrderPaymentResponse::status)
                .containsExactly(
                        tuple(1L, PaymentAttemptStatus.FAILED_INSUFFICIENT_BALANCE),
                        tuple(2L, PaymentAttemptStatus.PAID));
        OrderPaymentResponse paid = response.payments().get(1);
        assertThat(paid.amount()).isEqualTo(25_000);
        assertThat(paid.attemptedAt()).isEqualTo(paidAt);
        assertThat(paid.paidAt()).isEqualTo(paidAt);
        assertThat(paid.refundedAt()).isNull();
    }

    @Test
    @DisplayName("결제 내역 조회가 실패하면(payment 장애) 결제 내역 없이 paymentUnavailable = true로 응답한다")
    void payments_unavailable() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.of(order(500L)));
        given(paymentApi.getPaymentAttempts(500L))
                .willThrow(new DomainException(com.backend.global.exception.GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.payments()).isEmpty();
        assertThat(response.paymentUnavailable()).isTrue();
        assertThat(response.orderId()).isEqualTo(500L);
    }

    @Test
    @DisplayName("없는 주문이나 다른 회원의 주문이면 ORDER_NOT_FOUND")
    void notFound() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getOrderDetail(BUYER_ID, 500L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(OrderErrorCode.ORDER_NOT_FOUND.resultCode());
        verifyNoInteractions(paymentApi);   // 내 주문이 아니면 결제 내역을 조회하지 않는다
    }

    // ===== 배송 정보 (PR 4) =====

    /** 배송지가 있고 결제된 주문 */
    private Order paidOrderWithAddress(Long orderId) {
        Order order = Order.create(BUYER_ID, null, List.of(
                        new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)),
                ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞"),
                LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(order, "id", orderId);
        order.pay(LocalDateTime.now());
        return order;
    }

    @Test
    @DisplayName("배송 건이 있으면 delivery를 채우고, 배송지도 함께 담는다")
    void delivery_found() {
        LocalDateTime shippedAt = LocalDateTime.of(2026, 11, 2, 9, 0);
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.of(paidOrderWithAddress(500L)));
        given(deliveryApi.findByOrderId(500L)).willReturn(Optional.of(
                new DeliveryDto(70L, DeliveryStatus.SHIPPED, "CJ대한통운", "123456789012", shippedAt, null)));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.delivery()).isNotNull();
        assertThat(response.delivery().status()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(response.delivery().carrierName()).isEqualTo("CJ대한통운");
        assertThat(response.delivery().trackingNumber()).isEqualTo("123456789012");
        assertThat(response.delivery().shippedAt()).isEqualTo(shippedAt);
        assertThat(response.deliveryUnavailable()).isFalse();
        assertThat(response.shippingAddress().receiverName()).isEqualTo("홍길동");
        assertThat(response.shippingAddress().zipCode()).isEqualTo("06236");
        assertThat(response.deliveredAt()).isNull();
    }

    @Test
    @DisplayName("배송 건이 아직 없으면(결제 직후, data: null) delivery: null, deliveryUnavailable: false")
    void delivery_notYet() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.of(paidOrderWithAddress(500L)));
        given(deliveryApi.findByOrderId(500L)).willReturn(Optional.empty());

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.delivery()).isNull();
        assertThat(response.deliveryUnavailable()).isFalse();
    }

    @Test
    @DisplayName("delivery가 응답하지 못하면(502) 주문 상세는 그대로 응답하고 deliveryUnavailable: true, WARN 로그를 남긴다")
    void delivery_unavailable(CapturedOutput output) {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.of(paidOrderWithAddress(500L)));
        given(deliveryApi.findByOrderId(500L)).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.orderId()).isEqualTo(500L);
        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.delivery()).isNull();
        assertThat(response.deliveryUnavailable()).isTrue();
        assertThat(response.shippingAddress()).isNotNull();
        assertThat(output).contains("배송 정보 조회에 실패해 주문 상세를 배송 정보 없이 응답합니다: orderId=500");
    }

    @Test
    @DisplayName("배송지가 있어도 결제 전 주문은 delivery를 부르지 않는다")
    void delivery_notPaid() {
        Order order = Order.create(BUYER_ID, null, List.of(
                        new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)),
                ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", null, null),
                LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(order, "id", 500L);
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(order));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.delivery()).isNull();
        assertThat(response.deliveryUnavailable()).isFalse();
        assertThat(response.shippingAddress()).isNotNull();
        verifyNoInteractions(deliveryApi);
    }

    @Test
    @DisplayName("배송 완료가 기록된 주문은 deliveredAt을 담는다")
    void deliveredAt() {
        Order order = paidOrderWithAddress(500L);
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 11, 3, 15, 0);
        order.markDelivered(deliveredAt);
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(order));
        given(deliveryApi.findByOrderId(500L)).willReturn(Optional.of(
                new DeliveryDto(70L, DeliveryStatus.DELIVERED, "CJ대한통운", "123456789012", deliveredAt.minusDays(1), deliveredAt)));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(response.delivery().deliveredAt()).isEqualTo(deliveredAt);
    }

    // ===== 반품 필드·환불 상태 (TRSS-222 구매자 반품 요청) =====

    private static final LocalDateTime RETURN_PAID_AT = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final LocalDateTime RETURN_REQUESTED_AT = RETURN_PAID_AT.plusDays(2);

    /** 결제 후 반품 요청한 주문 (배송지 없는 주문이라 PAID면 요청 가능) */
    private Order returnRequestedOrder(Long orderId) {
        Order order = order(orderId);
        order.pay(RETURN_PAID_AT);
        order.requestReturn(ReturnReasonCode.OTHER, "색상이 사진과 달라요", RETURN_REQUESTED_AT);
        return order;
    }

    /** 반품 승인된 주문 (승인은 #333 범위라 상태·승인 시각만 만든다) */
    private Order returnedOrder(Long orderId) {
        Order order = returnRequestedOrder(orderId);
        ReflectionTestUtils.setField(order, "status", OrderStatus.RETURNED);
        ReflectionTestUtils.setField(order, "returnedAt", RETURN_REQUESTED_AT.plusDays(1));
        return order;
    }

    private static PaymentAttemptDto attempt(Long id, PaymentAttemptStatus status) {
        LocalDateTime refundedAt = status == PaymentAttemptStatus.REFUNDED ? RETURN_REQUESTED_AT.plusDays(1) : null;
        return new PaymentAttemptDto(id, status, 25_000, RETURN_PAID_AT, RETURN_PAID_AT, refundedAt);
    }

    @Test
    @DisplayName("반품 요청 중이면 요청 시각·사유 코드·상세 사유를 담고, 환불 상태는 NONE")
    void return_requested() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(returnRequestedOrder(500L)));
        given(paymentApi.getPaymentAttempts(500L)).willReturn(List.of(attempt(1L, PaymentAttemptStatus.PAID)));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(response.returnRequestedAt()).isEqualTo(RETURN_REQUESTED_AT);
        assertThat(response.returnReasonCode()).isEqualTo(ReturnReasonCode.OTHER);
        assertThat(response.returnReason()).isEqualTo("색상이 사진과 달라요");
        assertThat(response.returnedAt()).isNull();
        assertThat(response.refundStatus()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    @DisplayName("거절돼 PAID로 돌아온 주문은 거절 시각·거절 사유를 담고, 환불 상태는 NONE")
    void return_rejected() {
        Order order = returnRequestedOrder(500L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);   // 거절은 #333 범위라 결과 상태만 만든다
        ReflectionTestUtils.setField(order, "returnRejectedAt", RETURN_REQUESTED_AT.plusDays(1));
        ReflectionTestUtils.setField(order, "returnRejectReason", "사용 흔적이 있어 반품할 수 없습니다");
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(order));
        given(paymentApi.getPaymentAttempts(500L)).willReturn(List.of(attempt(1L, PaymentAttemptStatus.PAID)));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.returnRequestedAt()).isEqualTo(RETURN_REQUESTED_AT);
        assertThat(response.returnRejectedAt()).isEqualTo(RETURN_REQUESTED_AT.plusDays(1));
        assertThat(response.returnRejectReason()).isEqualTo("사용 흔적이 있어 반품할 수 없습니다");
        assertThat(response.refundStatus()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    @DisplayName("RETURNED이고 결제 시도에 REFUNDED가 있으면 환불 상태 COMPLETED, 승인 시각을 담는다")
    void refundStatus_completed() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(returnedOrder(500L)));
        given(paymentApi.getPaymentAttempts(500L)).willReturn(List.of(
                attempt(1L, PaymentAttemptStatus.FAILED_INSUFFICIENT_BALANCE), attempt(2L, PaymentAttemptStatus.REFUNDED)));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.refundStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(response.returnedAt()).isEqualTo(RETURN_REQUESTED_AT.plusDays(1));
    }

    @Test
    @DisplayName("RETURNED인데 결제 시도에 REFUNDED가 아직 없으면 환불 상태 PENDING")
    void refundStatus_pending() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(returnedOrder(500L)));
        given(paymentApi.getPaymentAttempts(500L)).willReturn(List.of(attempt(1L, PaymentAttemptStatus.PAID)));

        assertThat(useCase.getOrderDetail(BUYER_ID, 500L).refundStatus()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    @DisplayName("RETURNED인데 결제 내역 조회가 실패하면 환불 상태 UNKNOWN, paymentUnavailable = true")
    void refundStatus_unknown() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(returnedOrder(500L)));
        given(paymentApi.getPaymentAttempts(500L))
                .willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.refundStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(response.paymentUnavailable()).isTrue();
    }

    @Test
    @DisplayName("RETURNED가 아닌 주문은 결제 내역 조회가 실패해도 환불 상태 NONE (UNKNOWN은 반품 승인 주문만)")
    void refundStatus_notReturned_unavailable() {
        Order order = order(500L);
        order.pay(RETURN_PAID_AT);
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID)).willReturn(Optional.of(order));
        given(paymentApi.getPaymentAttempts(500L))
                .willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        OrderDetailResponse response = useCase.getOrderDetail(BUYER_ID, 500L);

        assertThat(response.refundStatus()).isEqualTo(RefundStatus.NONE);
        assertThat(response.paymentUnavailable()).isTrue();
    }
}
