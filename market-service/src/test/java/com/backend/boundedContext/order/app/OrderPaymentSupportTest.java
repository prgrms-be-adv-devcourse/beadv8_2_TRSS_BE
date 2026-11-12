package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.app.OrderPaymentSupport.PaymentAttempt;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 결과 반영 조건(PAYING + 같은 키)과 보상(결제 취소)
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentSupportTest {

    private static final Long ORDER_ID = 500L;
    private static final String KEY = "ORDER-500-PAY-first";
    private static final PayResultDto PAID = new PayResultDto(77L, PayResultStatus.PAID, 0, 0);

    @Mock OrderRepository orderRepository;
    @Mock OrderSupport orderSupport;
    @Mock PaymentApi paymentApi;
    @Mock EventPublisher eventPublisher;
    @Mock OrderCouponPort orderCouponPort;
    @Mock PlatformTransactionManager transactionManager;

    private OrderPaymentSupport support() {
        return new OrderPaymentSupport(orderRepository, orderSupport, paymentApi, eventPublisher, orderCouponPort, transactionManager);
    }

    private Order payingOrder(String key) {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.startPaying(key, LocalDateTime.now());
        lenient().when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("재확인 PayCommand는 주문에 저장한 키와 금액으로 만든다 (처음 요청과 같은 내용)")
    void toPayCommand_sameContent() {
        Order order = payingOrder(KEY);

        PayCommand command = support().toPayCommand(order);

        assertThat(command).isEqualTo(new PayCommand(ORDER_ID, order.getOrderNumber(), 3L, 20_000, 0, 0,
                order.getExpiresAt(), KEY));
    }

    @Test
    @DisplayName("다른 쪽이 먼저 PAID로 반영했으면(같은 키) 다시 반영하지 않고 결제 취소도 하지 않는다")
    void alreadyPaidWithSameKey_noop() {
        Order order = payingOrder(KEY);
        order.completePayment(LocalDateTime.now());
        given(paymentApi.pay(any())).willReturn(PAID);

        PaymentAttempt attempt = support().requestAndApply(support().toPayCommand(order), LocalDateTime.now());

        assertThat(attempt.order().getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(orderCouponPort, eventPublisher);
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("PAID인데 주문이 이 키로 결제 중도 결제 완료도 아니면(예외 상태) 결제를 취소(보상)한다")
    void paidButOrderNotPaying_compensates() {
        Order order = payingOrder(KEY);
        PayCommand command = support().toPayCommand(order);
        // 예외 상태를 만든다: 결제 중이 아닌 상태(CANCELED)로 바뀐 주문
        ReflectionTestUtils.setField(order, "status", OrderStatus.CREATED);
        order.cancel(OrderCancelType.USER, "테스트", LocalDateTime.now());
        given(paymentApi.pay(any())).willReturn(PAID);

        support().requestAndApply(command, LocalDateTime.now());

        verify(paymentApi).cancelPayment(KEY);
        verifyNoInteractions(orderCouponPort, eventPublisher);
    }

    @Test
    @DisplayName("주문이 다른 키로 결제 중이면 이 키의 PAID 결과는 반영하지 않고 결제를 취소한다")
    void paidWithOtherKey_compensates() {
        Order order = payingOrder("ORDER-500-PAY-second");
        PayCommand oldCommand = new PayCommand(ORDER_ID, order.getOrderNumber(), 3L, 20_000, 0, 0, order.getExpiresAt(), KEY);
        given(paymentApi.pay(any())).willReturn(PAID);

        support().requestAndApply(oldCommand, LocalDateTime.now());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYING);
        verify(paymentApi).cancelPayment(KEY);
    }

    @Test
    @DisplayName("FAILED_* 결과는 결제 취소를 부르지 않는다")
    void failed_noCompensation() {
        Order order = payingOrder(KEY);
        given(paymentApi.pay(any())).willReturn(new PayResultDto(null, PayResultStatus.FAILED_ORDER_EXPIRED, 0, 0));

        support().requestAndApply(support().toPayCommand(order), LocalDateTime.now());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(paymentApi, never()).cancelPayment(any());
    }
}
