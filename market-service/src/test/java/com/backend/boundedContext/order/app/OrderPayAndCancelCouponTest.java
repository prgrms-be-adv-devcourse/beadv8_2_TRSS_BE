package com.backend.boundedContext.order.app;

import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.OrderPayResponse;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.out.ProductApi;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 결제와 결제 전 취소의 쿠폰 처리 (사용 처리, 해제)
 */
@ExtendWith(MockitoExtension.class)
class OrderPayAndCancelCouponTest {

    private static final Long BUYER_ID = 3L;
    private static final Long ORDER_ID = 500L;

    @Mock OrderRepository orderRepository;
    @Mock OrderSupport orderSupport;
    @Mock PaymentApi paymentApi;
    @Mock MemberApi memberApi;
    @Mock EventPublisher eventPublisher;
    @Mock OrderCouponPort orderCouponPort;
    @Mock ProductApi productApi;

    /** 옵션 100, 20,000원 x 2 = 40,000원. 상품 쿠폰 10,000원, 장바구니 쿠폰 3,000원 → 결제 27,000원 */
    private Order discountedOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 20_000, 2)), LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().getFirst(), "id", 1001L);
        order.applyProductCouponDiscount(1001L, 10_000);
        order.applyCartCouponDiscount(3_000);
        return order;
    }

    private OrderPayOrderUseCase payUseCase() {
        return new OrderPayOrderUseCase(orderRepository, orderSupport, paymentApi, memberApi, eventPublisher, orderCouponPort);
    }

    @Test
    @DisplayName("결제: 쿠폰을 사용 처리한 뒤 할인을 반영한 결제 금액(payAmount)으로 결제를 요청한다")
    void payUsesCouponsBeforePayment() {
        Order order = discountedOrder();
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));
        given(paymentApi.pay(any())).willReturn(new PayResultDto(77L, PayResultStatus.PAID, 0, 0));
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(mock(Cart.class)));

        OrderPayResponse response = payUseCase().pay(BUYER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.paidAmount()).isEqualTo(27_000);
        InOrder inOrder = inOrder(orderCouponPort, paymentApi);
        inOrder.verify(orderCouponPort).useReservedCoupons(eq(ORDER_ID), any(LocalDateTime.class));
        inOrder.verify(paymentApi).pay(argThat(cmd -> cmd.amount() == 27_000));
    }

    @Test
    @DisplayName("결제: 예약 뒤 쿠폰이 중지돼 사용 처리가 409-COUPON_NOT_ACTIVE면 결제 서비스를 부르지 않는다 (돈이 움직이지 않음)")
    void payStopsWhenCouponNotActive() {
        Order order = discountedOrder();
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));
        willThrow(new DomainException(CouponErrorCode.COUPON_NOT_ACTIVE))
                .given(orderCouponPort).useReservedCoupons(eq(ORDER_ID), any(LocalDateTime.class));

        assertThatThrownBy(() -> payUseCase().pay(BUYER_ID, ORDER_ID))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
        verifyNoInteractions(paymentApi, eventPublisher);
    }

    @Test
    @DisplayName("결제 전 취소: 재고를 되돌린 뒤 주문의 예약 쿠폰을 해제한다")
    void cancelReleasesCoupons() {
        Order order = discountedOrder();
        LocalDateTime now = LocalDateTime.now();

        new OrderCancelSupport(productApi, orderCouponPort).cancel(order, OrderCancelType.USER, "단순 변심", now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        InOrder inOrder = inOrder(productApi, orderCouponPort);
        inOrder.verify(productApi).restoreStock(any(), any());
        inOrder.verify(orderCouponPort).releaseReservedCoupons(ORDER_ID, now);
    }

    @Test
    @DisplayName("결제 전 취소: 이미 결제된 주문이면 409-ORDER_NOT_CANCELABLE이고 쿠폰을 해제하지 않는다")
    void cancelPaidOrderKeepsCoupons() {
        Order order = discountedOrder();
        order.pay(LocalDateTime.now());

        assertThatThrownBy(() -> new OrderCancelSupport(productApi, orderCouponPort)
                .cancel(order, OrderCancelType.USER, "단순 변심", LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-ORDER_NOT_CANCELABLE");
        verify(orderCouponPort, never()).releaseReservedCoupons(any(), any());
    }
}
