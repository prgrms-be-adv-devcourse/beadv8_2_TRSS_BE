package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.InsufficientBalanceResponse;
import com.backend.boundedContext.order.in.dto.OrderPayResponse;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderPaidEvent;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 결제 API: 결제 시작(PAYING 커밋) → 결제 요청 → 결과 반영의 단위 테스트
 * 트랜잭션 관리자는 가짜(mock)라 롤백은 일어나지 않는다. 롤백이 필요한 확인은 통합 테스트(OrderPayingIntegrationTest)에서 한다
 */
@ExtendWith(MockitoExtension.class)
class OrderPayOrderUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderSupport orderSupport;
    @Mock PaymentApi paymentApi;
    @Mock MemberApi memberApi;
    @Mock EventPublisher eventPublisher;
    @Mock OrderCouponPort orderCouponPort;
    @Mock PlatformTransactionManager transactionManager;

    private static final Long BUYER_ID = 3L;
    private static final Long ORDER_ID = 500L;
    private static final PayResultDto PAID = new PayResultDto(77L, PayResultStatus.PAID, 0, 0);

    private OrderPayOrderUseCase useCase() {
        OrderPaymentSupport support = new OrderPaymentSupport(orderRepository, orderSupport, paymentApi, eventPublisher,
                orderCouponPort, transactionManager);
        return new OrderPayOrderUseCase(orderRepository, orderSupport, support, memberApi, orderCouponPort, transactionManager);
    }

    /** 장바구니 항목 1번(옵션 100, 10,000원 × 2)으로 방금 만든 주문 */
    private Order order(LocalDateTime createdAt) {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), createdAt, 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    // 결제 시작은 구매자 조건으로, 결과 반영은 주문 ID로 잠근다
    private Order stubOrder(Order order) {
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));
        lenient().when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("결제에 성공하면 주문은 PAID, 결제 금액은 주문 총액, 장바구니 항목은 삭제된다")
    void pay() {
        Order order = stubOrder(order(LocalDateTime.now()));
        Cart cart = mock(Cart.class);
        given(paymentApi.pay(any())).willReturn(PAID);
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));

        OrderPayResponse response = useCase().pay(BUYER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.paymentId()).isEqualTo(77L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderSupport).getSaleableOptions(List.of(100L));
        // 배송비·장바구니 쿠폰이 없는 주문은 둘 다 0으로 보내고, 주문에 저장한 이번 시도의 키를 쓴다
        verify(paymentApi).pay(argThat(cmd -> cmd.amount() == 20_000
                && cmd.shippingFee() == 0
                && cmd.couponSubsidyAmount() == 0
                && cmd.orderId().equals(ORDER_ID)
                && cmd.orderNumber().equals(order.getOrderNumber())
                && cmd.idempotencyKey().equals(order.getPaymentIdempotencyKey())));
        verify(cart).removeItems(List.of(1L));
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("쿠폰은 결제 요청 전에 쓸 수 있는지 확인하고, 사용 처리는 결제 성공(PAID) 반영 때 한다")
    void pay_validatesCouponsBeforeAndUsesAfterPayment() {
        Order order = order(LocalDateTime.now());   // 20,000원
        ReflectionTestUtils.setField(order.getItems().get(0), "id", 1L);
        order.applyProductCouponDiscount(1L, 5_000);
        stubOrder(order);
        given(paymentApi.pay(any())).willReturn(PAID);

        useCase().pay(BUYER_ID, ORDER_ID);

        InOrder inOrder = inOrder(orderCouponPort, paymentApi);
        inOrder.verify(orderCouponPort).validateReservedCouponsUsable(eq(ORDER_ID), any());
        inOrder.verify(paymentApi).pay(argThat(cmd -> cmd.amount() == 15_000));
        inOrder.verify(orderCouponPort).useReservedCoupons(eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("결제 요청에 결제 금액(배송비 포함), 배송비, 장바구니 쿠폰 지원금(= 장바구니 쿠폰 할인액)을 싣는다")
    void pay_sendsShippingFeeAndCouponSubsidy() {
        Order order = order(LocalDateTime.now());   // 20,000원
        order.applyShippingFee(2_500);
        order.applyCartCouponDiscount(1_000);
        stubOrder(order);
        given(paymentApi.pay(any())).willReturn(PAID);

        OrderPayResponse response = useCase().pay(BUYER_ID, ORDER_ID);

        ArgumentCaptor<PayCommand> command = ArgumentCaptor.forClass(PayCommand.class);
        verify(paymentApi).pay(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(order.getPayAmount()).isEqualTo(21_500);
        assertThat(command.getValue().shippingFee()).isEqualTo(order.getShippingFee()).isEqualTo(2_500);
        assertThat(command.getValue().couponSubsidyAmount()).isEqualTo(order.getCartCouponDiscountAmount()).isEqualTo(1_000);
        assertThat(response.paidAmount()).isEqualTo(21_500);
    }

    @Test
    @DisplayName("예약 뒤 쿠폰이 중지돼 확인이 409-COUPON_NOT_ACTIVE면 결제 서비스를 부르지 않는다")
    void pay_couponNotActive_noPayment() {
        stubOrder(order(LocalDateTime.now()));
        willThrow(new DomainException("409-COUPON_NOT_ACTIVE", "사용 기간이 아니거나 중지된 쿠폰입니다.", null))
                .given(orderCouponPort).validateReservedCouponsUsable(eq(ORDER_ID), any());

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
        verify(paymentApi, never()).pay(any());
        verify(transactionManager).rollback(any());
    }

    @Test
    @DisplayName("탈퇴한 회원이면 주문을 잠그지 않고 결제도 호출하지 않으며 401 UNAUTHORIZED (탈퇴 후 남은 Access Token)")
    void withdrawnMember() {
        willThrow(new DomainException(GlobalErrorCode.UNAUTHORIZED)).given(memberApi).checkActiveMember(BUYER_ID);

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("401-UNAUTHORIZED");

        verifyNoInteractions(orderRepository, paymentApi);
    }

    @Test
    @DisplayName("잔액이 부족하면 주문은 PAYMENT_FAILED, 402-INSUFFICIENT_BALANCE와 부족액·충전 권장액·남은 시간을 주고 결제 취소는 부르지 않는다")
    void insufficientBalance() {
        Order order = stubOrder(order(LocalDateTime.now()));
        given(paymentApi.pay(any()))
                .willReturn(new PayResultDto(null, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 18_000, 20_000));

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("402-INSUFFICIENT_BALANCE");
                    InsufficientBalanceResponse data = (InsufficientBalanceResponse) e.getData();
                    assertThat(data.shortage()).isEqualTo(18_000);
                    assertThat(data.recommendedTopupAmount()).isEqualTo(20_000);
                    assertThat(data.remainingSeconds()).isPositive();
                });
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(orderSupport, never()).findCart(any());
        verify(orderCouponPort, never()).useReservedCoupons(any(), any());
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("결제 서비스가 주문 만료로 판단하면(FAILED_ORDER_EXPIRED) 주문은 PAYMENT_FAILED, 409-ORDER_EXPIRED")
    void failedOrderExpired() {
        Order order = stubOrder(order(LocalDateTime.now()));
        given(paymentApi.pay(any()))
                .willReturn(new PayResultDto(null, PayResultStatus.FAILED_ORDER_EXPIRED, 0, 0));

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .extracting("resultCode").isEqualTo("409-ORDER_EXPIRED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("결과를 모르면(응답 시간 초과 등 502) 주문을 PAYING으로 두고 결제 확인 중(PAYING)으로 응답한다")
    void unknownResult_staysPaying() {
        Order order = stubOrder(order(LocalDateTime.now()));
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        OrderPayResponse response = useCase().pay(BUYER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYING);
        assertThat(response.paidAt()).isNull();
        assertThat(response.paymentId()).isNull();
        assertThat(response.paidAmount()).isEqualTo(20_000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYING);
        assertThat(order.getPaymentIdempotencyKey()).startsWith("ORDER-500-PAY-");
        assertThat(order.getPayingStartedAt()).isNotNull();
        verify(orderCouponPort, never()).useReservedCoupons(any(), any());
        verifyNoInteractions(eventPublisher);
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("결제 서비스의 잠금 충돌(409-LOCK_CONFLICT)은 같은 키의 다른 요청이 결제됐을 수 있어 결과 불명(PAYING)으로 본다")
    void lockConflict_isUnknown() {
        Order order = stubOrder(order(LocalDateTime.now()));
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.LOCK_CONFLICT));

        OrderPayResponse response = useCase().pay(BUYER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYING);
    }

    @Test
    @DisplayName("결제 서비스가 요청을 거절하면(4xx, 예: 404-WALLET_NOT_FOUND) 결제가 없으므로 PAYMENT_FAILED로 두고 같은 오류를 준다")
    void rejected_paymentFailed() {
        Order order = stubOrder(order(LocalDateTime.now()));
        given(paymentApi.pay(any()))
                .willThrow(new DomainException("404-WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", null));

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .extracting("resultCode").isEqualTo("404-WALLET_NOT_FOUND");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("결과 반영 트랜잭션이 실패하면 주문을 PAYING으로 두고 결제 확인 중으로 응답한다 (재확인 스케줄러가 다시 반영)")
    void applyFails_respondsPaying() {
        stubOrder(orderWithAddress());
        given(paymentApi.pay(any())).willReturn(PAID);
        willThrow(new IllegalStateException("outbox 저장 실패")).given(eventPublisher).publish(any());

        OrderPayResponse response = useCase().pay(BUYER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYING);
        verify(transactionManager).rollback(any());
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("결제 확인 중(PAYING)인 주문에 다시 결제하면 409-PAYMENT_IN_PROGRESS이고 결제를 부르지 않는다")
    void paying_rejectsRepay() {
        Order order = order(LocalDateTime.now());
        order.startPaying("ORDER-500-PAY-first", LocalDateTime.now());
        stubOrder(order);

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .extracting("resultCode").isEqualTo("409-PAYMENT_IN_PROGRESS");
        verifyNoInteractions(paymentApi);
        assertThat(order.getPaymentIdempotencyKey()).isEqualTo("ORDER-500-PAY-first");
    }

    @Test
    @DisplayName("만료된 주문이면 결제 모듈을 호출하지 않고 409-ORDER_EXPIRED")
    void expired() {
        stubOrder(order(LocalDateTime.now().minusMinutes(20)));

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("409-ORDER_EXPIRED"));
        verifyNoInteractions(paymentApi);
    }

    @Test
    @DisplayName("판매 불가 상품이 있으면 결제하지 않고 409-PRODUCT_NOT_ON_SALE")
    void notSaleable() {
        stubOrder(order(LocalDateTime.now()));
        given(orderSupport.getSaleableOptions(List.of(100L)))
                .willThrow(new DomainException(OrderErrorCode.PRODUCT_NOT_ON_SALE));

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("409-PRODUCT_NOT_ON_SALE"));
        verifyNoInteractions(paymentApi);
    }

    // ===== 결제 완료 이벤트 (PR 4) =====

    /** 배송지와 함께 방금 만든 주문 (장바구니 항목 1번, 옵션 100, 상품 10, 판매자 2, 10,000원 × 2) */
    private Order orderWithAddress() {
        Order order = Order.create(BUYER_ID, null, List.of(
                        new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)),
                ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞"),
                LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", 900L);
        return order;
    }

    @Test
    @DisplayName("배송지가 있는 주문의 결제에 성공하면 OrderPaidEvent 1건을 발행한다 (항목·배송지 값)")
    void pay_withAddress_publishesPaidEvent() {
        Order order = stubOrder(orderWithAddress());
        given(paymentApi.pay(any())).willReturn(PAID);
        ArgumentCaptor<OrderPaidEvent> captor = ArgumentCaptor.forClass(OrderPaidEvent.class);

        useCase().pay(BUYER_ID, ORDER_ID);

        verify(eventPublisher).publish(captor.capture());
        OrderPaidEvent event = captor.getValue();
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.orderNumber()).isEqualTo(order.getOrderNumber());
        assertThat(event.buyerId()).isEqualTo(BUYER_ID);
        assertThat(event.paidAt()).isEqualTo(order.getPaidAt()).isNotNull();
        assertThat(event.shippingAddress()).isEqualTo(new ShippingAddressDto(
                "홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞"));
        assertThat(event.items()).containsExactly(
                new PaidOrderItemDto(900L, 10L, 100L, 2L, "상점", "립스틱", "기본", 2));
    }

    @Test
    @DisplayName("배송지 없는 주문은 결제에 성공해도 OrderPaidEvent를 발행하지 않는다")
    void pay_withoutAddress_noEvent() {
        stubOrder(order(LocalDateTime.now()));
        given(paymentApi.pay(any())).willReturn(PAID);

        useCase().pay(BUYER_ID, ORDER_ID);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("배송지가 있어도 결제가 실패(잔액 부족)하면 OrderPaidEvent를 발행하지 않는다")
    void pay_withAddress_insufficientBalance_noEvent() {
        stubOrder(orderWithAddress());
        given(paymentApi.pay(any()))
                .willReturn(new PayResultDto(null, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 18_000, 20_000));

        assertThatThrownBy(() -> useCase().pay(BUYER_ID, ORDER_ID))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("402-INSUFFICIENT_BALANCE"));
        verifyNoInteractions(eventPublisher);
    }
}
