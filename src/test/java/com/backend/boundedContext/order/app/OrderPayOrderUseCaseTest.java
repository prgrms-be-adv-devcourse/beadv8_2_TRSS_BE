package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.InsufficientBalanceResponse;
import com.backend.boundedContext.order.in.dto.OrderPayResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderPayOrderUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderSupport orderSupport;
    @Mock PaymentApi paymentApi;
    @InjectMocks
    OrderPayOrderUseCase useCase;

    private static final Long BUYER_ID = 3L;
    private static final Long ORDER_ID = 500L;

    /** 장바구니 항목 1번(옵션 100, 10,000원 × 2)으로 방금 만든 주문 */
    private Order order(LocalDateTime createdAt) {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), createdAt, 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("결제에 성공하면 주문은 PAID, 결제 금액은 주문 총액, 장바구니 항목은 삭제된다")
    void pay() {
        Order order = order(LocalDateTime.now());
        Cart cart = mock(Cart.class);
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID)).willReturn(Optional.of(order));
        given(paymentApi.pay(any())).willReturn(new PayResultDto(77L, PayResultStatus.PAID, 0, 0));
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));

        OrderPayResponse response = useCase.pay(BUYER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.paymentId()).isEqualTo(77L);
        verify(orderSupport).getSaleableOptions(List.of(100L));
        verify(paymentApi).pay(argThat(cmd -> cmd.amount() == 20_000 && cmd.orderId().equals(ORDER_ID)));
        verify(cart).removeItems(List.of(1L));
    }

    @Test
    @DisplayName("잔액이 부족하면 402-INSUFFICIENT_BALANCE와 부족액·충전 권장액·남은 시간을 준다")
    void insufficientBalance() {
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID))
                .willReturn(Optional.of(order(LocalDateTime.now())));
        given(paymentApi.pay(any()))
                .willReturn(new PayResultDto(null, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 18_000, 20_000));

        assertThatThrownBy(() -> useCase.pay(BUYER_ID, ORDER_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo("402-INSUFFICIENT_BALANCE");
                    InsufficientBalanceResponse data = (InsufficientBalanceResponse) e.getData();
                    assertThat(data.shortage()).isEqualTo(18_000);
                    assertThat(data.recommendedTopupAmount()).isEqualTo(20_000);
                    assertThat(data.remainingSeconds()).isPositive();
                });
        verify(orderSupport, never()).findCart(any());
    }

    @Test
    @DisplayName("만료된 주문이면 결제 모듈을 호출하지 않고 409-ORDER_EXPIRED")
    void expired() {
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID))
                .willReturn(Optional.of(order(LocalDateTime.now().minusMinutes(20))));

        assertThatThrownBy(() -> useCase.pay(BUYER_ID, ORDER_ID))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("409-ORDER_EXPIRED"));
        verifyNoInteractions(paymentApi);
    }

    @Test
    @DisplayName("판매 불가 상품이 있으면 결제하지 않고 409-PRODUCT_NOT_ON_SALE")
    void notSaleable() {
        given(orderRepository.findByIdAndBuyerIdForUpdate(ORDER_ID, BUYER_ID))
                .willReturn(Optional.of(order(LocalDateTime.now())));
        given(orderSupport.getSaleableOptions(List.of(100L)))
                .willThrow(new DomainException(OrderErrorCode.PRODUCT_NOT_ON_SALE));

        assertThatThrownBy(() -> useCase.pay(BUYER_ID, ORDER_ID))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("409-PRODUCT_NOT_ON_SALE"));
        verifyNoInteractions(paymentApi);
    }
}
