package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.order.app.OrderPaymentSupport.PaymentAttempt;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.payment.dto.PayCommand;

/**
 * 결제 중(PAYING) 주문 재확인: 재확인은 첫 결제 요청과 다른 반영 규칙(recheckAndApply)을 쓴다 (TRSS-307)
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrderRecheckPayingOrdersUseCaseTest {

    private static final Long ORDER_ID = 500L;

    @Mock OrderRepository orderRepository;
    @Mock OrderPaymentSupport orderPaymentSupport;

    private OrderRecheckPayingOrdersUseCase useCase() {
        return new OrderRecheckPayingOrdersUseCase(orderRepository, orderPaymentSupport, 30, 10);
    }

    private Order payingOrder(LocalDateTime payingStartedAt) {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 1)), payingStartedAt, 15);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.startPaying("ORDER-500-PAY-first", payingStartedAt);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("재확인은 재확인 규칙(recheckAndApply)으로 부른다. 결과 불명(내부 키 인증 실패 포함)이면 false")
    void recheck_usesRecheckRule_unknownIsFalse() {
        payingOrder(LocalDateTime.now().minusMinutes(1));
        PayCommand command = new PayCommand(ORDER_ID, "ORD-1", 3L, 10_000, 0, 0, LocalDateTime.now(), "ORDER-500-PAY-first");
        given(orderPaymentSupport.toPayCommand(any())).willReturn(command);
        given(orderPaymentSupport.recheckAndApply(any(), any())).willReturn(PaymentAttempt.unknown());

        assertThat(useCase().recheck(ORDER_ID, LocalDateTime.now())).isFalse();
        verify(orderPaymentSupport).recheckAndApply(any(), any());
        verify(orderPaymentSupport, never()).requestAndApply(any(), any());
    }

    @Test
    @DisplayName("결과 불명이 payingAlertMinutes를 넘기면 ERROR로 알린다 (내부 키 인증 실패가 계속돼도 같은 알림)")
    void recheck_unknownStale_alerts(CapturedOutput output) {
        payingOrder(LocalDateTime.now().minusMinutes(11));
        given(orderPaymentSupport.toPayCommand(any())).willReturn(
                new PayCommand(ORDER_ID, "ORD-1", 3L, 10_000, 0, 0, LocalDateTime.now(), "ORDER-500-PAY-first"));
        given(orderPaymentSupport.recheckAndApply(any(), any())).willReturn(PaymentAttempt.unknown());

        assertThat(useCase().recheck(ORDER_ID, LocalDateTime.now())).isFalse();
        assertThat(output).contains("결제 중 상태가 10분 넘게 정리되지 않았습니다: orderId=500");
    }
}
