package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.in.dto.OrderPaymentResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderGetOrderDetailUseCaseTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    PaymentApi paymentApi;
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
    @DisplayName("없는 주문이나 다른 회원의 주문이면 ORDER_NOT_FOUND")
    void notFound() {
        given(orderRepository.findWithItemsByIdAndBuyerId(500L, BUYER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getOrderDetail(BUYER_ID, 500L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(OrderErrorCode.ORDER_NOT_FOUND.resultCode());
        verifyNoInteractions(paymentApi);   // 내 주문이 아니면 결제 내역을 조회하지 않는다
    }
}