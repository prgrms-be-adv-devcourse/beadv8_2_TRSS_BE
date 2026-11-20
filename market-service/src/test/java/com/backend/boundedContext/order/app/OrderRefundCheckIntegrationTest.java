package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 반품 환불 확인(TRSS-284)을 실제 DB로 확인한다. 결제 서비스는 목으로 둔다.
 * - 결제 시도에 REFUNDED가 있으면 환불 시각을 주문에 기록하고, 다시 확인해도 바꾸지 않는다
 * - 환불 전이면 그대로 두고, 반품 승인 후 refundAlertMinutes(60분)가 지나면 ERROR 로그
 * - 결제 서비스 장애면 기록하지 않고 다음 실행으로 넘긴다
 * - 회원 탈퇴 검사: 환불 전 반품 주문이 있으면 진행 중, 환불 기록 뒤에는 아님
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class OrderRefundCheckIntegrationTest {

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private MemberApi memberApi;
    @MockitoBean private ProductApi productApi;

    private final long buyerId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final long optionId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();
    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    @Test
    @DisplayName("결제 시도에 REFUNDED가 있으면 환불 시각을 기록하고, 다시 확인해도 그대로 둔다")
    void recordsRefund() {
        long orderId = saveReturnedOrder(now.minusMinutes(5));
        LocalDateTime refundedAt = now.minusMinutes(4);
        given(paymentApi.getPaymentAttempts(orderId)).willReturn(List.of(attempt(PaymentAttemptStatus.REFUNDED, refundedAt)));

        assertThat(orderFacade.checkRefund(orderId, now)).isTrue();
        assertThat(refundedAt(orderId)).isEqualTo(refundedAt);

        assertThat(orderFacade.checkRefund(orderId, now)).isFalse();   // 이미 기록됨
        assertThat(refundedAt(orderId)).isEqualTo(refundedAt);
    }

    @Test
    @DisplayName("환불 전이면 그대로 두고, 반품 승인 후 60분이 지났으면 ERROR 로그로 한 번만 알린다")
    void alertsWhenRefundIsLate(CapturedOutput output) {
        long recent = saveReturnedOrder(now.minusMinutes(5));
        long late = saveReturnedOrder(now.minusMinutes(61));
        given(paymentApi.getPaymentAttempts(recent)).willReturn(List.of(attempt(PaymentAttemptStatus.PAID, null)));
        given(paymentApi.getPaymentAttempts(late)).willReturn(List.of(attempt(PaymentAttemptStatus.PAID, null)));

        assertThat(orderFacade.checkRefund(recent, now)).isFalse();
        assertThat(orderFacade.checkRefund(late, now)).isFalse();

        assertThat(refundedAt(recent)).isNull();
        assertThat(refundedAt(late)).isNull();
        assertThat(output.getOut()).contains("환불되지 않았습니다: orderId=" + late)
                .doesNotContain("환불되지 않았습니다: orderId=" + recent);
        assertThat(refundAlertedAt(late)).isEqualTo(now);
        assertThat(refundAlertedAt(recent)).isNull();

        // 다음 실행(5분 뒤)에도 환불 전이지만 알림은 다시 내지 않는다
        orderFacade.checkRefund(late, now.plusMinutes(5));
        assertThat(output.getOut().split("환불되지 않았습니다: orderId=" + late + ",", -1)).hasSize(2);
        assertThat(refundAlertedAt(late)).isEqualTo(now);
    }

    @Test
    @DisplayName("알림을 보낸 뒤에도 환불을 계속 확인해서, 늦게 온 환불도 기록한다")
    void recordsLateRefundAfterAlert() {
        long orderId = saveReturnedOrder(now.minusMinutes(61));
        given(paymentApi.getPaymentAttempts(orderId)).willReturn(List.of(attempt(PaymentAttemptStatus.PAID, null)));
        orderFacade.checkRefund(orderId, now);

        given(paymentApi.getPaymentAttempts(orderId)).willReturn(List.of(attempt(PaymentAttemptStatus.REFUNDED, now.plusMinutes(1))));
        assertThat(orderFacade.checkRefund(orderId, now.plusMinutes(5))).isTrue();
        assertThat(refundedAt(orderId)).isEqualTo(now.plusMinutes(1));
    }

    @Test
    @DisplayName("결제 서비스가 응답하지 않으면 기록하지 않고 다음 실행으로 넘긴다")
    void paymentDown() {
        long orderId = saveReturnedOrder(now.minusMinutes(5));
        given(paymentApi.getPaymentAttempts(orderId)).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        assertThat(orderFacade.checkRefund(orderId, now)).isFalse();
        assertThat(refundedAt(orderId)).isNull();
    }

    @Test
    @DisplayName("확인 대상: 반품 승인 뒤 60초가 지났고 환불 기록이 없는 주문만")
    void findTargets() {
        long target = saveReturnedOrder(now.minusMinutes(5));
        long tooRecent = saveReturnedOrder(now.minusSeconds(10));
        long refunded = saveReturnedOrder(now.minusMinutes(5));
        transactionTemplate.executeWithoutResult(status ->
                orderRepository.findById(refunded).orElseThrow().markRefunded(now.minusMinutes(4)));

        List<Long> ids = orderFacade.findRefundCheckTargetIds(now, target - 1, 100);

        assertThat(ids).contains(target).doesNotContain(tooRecent, refunded);
    }

    @Test
    @DisplayName("회원 탈퇴 검사: 환불 전 반품 주문이 있으면 진행 중으로 보고, 환불 기록 뒤에는 아니다")
    void withdrawalCheck() {
        long orderId = saveReturnedOrder(now.minusMinutes(5));
        assertThat(orderFacade.hasActiveOrders(buyerId)).isTrue();

        given(paymentApi.getPaymentAttempts(orderId)).willReturn(List.of(attempt(PaymentAttemptStatus.REFUNDED, now.minusMinutes(4))));
        orderFacade.checkRefund(orderId, now);

        assertThat(orderFacade.hasActiveOrders(buyerId)).isFalse();
    }

    // 배송 완료 → 반품 요청 → 반품 승인(returnedAt)까지 마친 주문
    private long saveReturnedOrder(LocalDateTime returnedAt) {
        Long id = transactionTemplate.execute(status -> {
            LocalDateTime deliveredAt = returnedAt.minusDays(2);
            Order order = Order.create(buyerId, null,
                    List.of(new OrderItemSpec(null, 10L, optionId, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                    ShippingAddress.of("김환불", "01012345678", "06236", "서울 강남구 환불로 1", "101호", null),
                    deliveredAt.minusDays(1).minusMinutes(1), 15);
            order.pay(deliveredAt.minusDays(1));
            order.markDelivered(deliveredAt);
            order.requestReturn(com.backend.shared.order.dto.ReturnReasonCode.DEFECTIVE, null, returnedAt.minusDays(1));
            Order saved = orderRepository.save(order);
            saved.approveReturn(List.of(new com.backend.boundedContext.order.domain.ReturnItemDecision(
                    saved.getItems().getFirst().getId(), true)), returnedAt);
            return saved.getId();
        });
        orderIds.add(id);
        return id;
    }

    private LocalDateTime refundedAt(long orderId) {
        return jdbcTemplate.queryForObject("select refunded_at from market_order where id = ?", LocalDateTime.class, orderId);
    }

    private LocalDateTime refundAlertedAt(long orderId) {
        return jdbcTemplate.queryForObject("select refund_alerted_at from market_order where id = ?", LocalDateTime.class, orderId);
    }

    private static PaymentAttemptDto attempt(PaymentAttemptStatus status, LocalDateTime refundedAt) {
        return new PaymentAttemptDto(1L, status, 10_000, LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(3), refundedAt);
    }
}
