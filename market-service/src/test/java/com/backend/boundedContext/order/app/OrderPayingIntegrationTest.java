package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.OrderPayResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;

/**
 * 결제 중(PAYING) 상태와 재확인 (TRSS-318). 실제 DB로 트랜잭션 분리·커밋을 확인한다
 * - 결제 결과를 모르면 PAYING이 커밋되고, 재확인이 같은 키·같은 내용으로 pay를 다시 불러 정리한다
 * - 결과 반영은 PAYING이고 같은 키일 때만 한 번
 * - PAYING 중 재결제·취소·만료·판매 중지 취소는 동작하지 않는다
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        "custom.order.payingRecheckSeconds=30"
})
class OrderPayingIntegrationTest {

    private static final PayResultDto PAID = new PayResultDto(900L, PayResultStatus.PAID, 0, 0);

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private MemberApi memberApi;
    @MockitoBean private ProductApi productApi;

    private final long buyerId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final long productId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final long optionId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(productApi.getOptions(anyCollection())).willReturn(List.of(
                new OptionSnapshotDto(optionId, productId, 2L, "립스틱", "기본", 10_000, 10, true, null, null)));
    }

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    @Test
    @DisplayName("응답 시간 초과: 주문은 PAYING으로 커밋되고(키·시작 시각 저장) 결제 확인 중으로 응답한다")
    void timeout_commitsPaying() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        OrderPayResponse response = orderFacade.payOrder(buyerId, orderId);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYING);
        Order order = find(orderId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYING);
        assertThat(order.getPaymentIdempotencyKey()).startsWith("ORDER-" + orderId + "-PAY-");
        assertThat(order.getPayingStartedAt()).isNotNull();
        assertThat(outboxCount(orderId)).isZero();
    }

    @Test
    @DisplayName("재확인: 30초가 지난 PAYING 주문에 같은 키·같은 내용으로 pay를 다시 불러 PAID로 반영하고 OrderPaidEvent 1건")
    void recheck_sameKey_paid() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        orderFacade.payOrder(buyerId, orderId);
        LocalDateTime now = LocalDateTime.now();
        // 30초가 지나지 않았으면 대상이 아니다
        assertThat(orderFacade.findPayingRecheckTargetIds(now, orderId - 1, 10)).doesNotContain(orderId);
        movePayingStartedAt(orderId, now.minusSeconds(31));
        assertThat(orderFacade.findPayingRecheckTargetIds(now, orderId - 1, 10)).contains(orderId);

        willReturn(PAID).given(paymentApi).pay(any());
        assertThat(orderFacade.recheckPayingOrder(orderId, now)).isTrue();

        ArgumentCaptor<PayCommand> commands = ArgumentCaptor.forClass(PayCommand.class);
        verify(paymentApi, times(2)).pay(commands.capture());
        assertThat(commands.getAllValues().get(1)).isEqualTo(commands.getAllValues().get(0));   // 같은 키·같은 내용
        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(find(orderId).getPaidAt()).isNotNull();
        assertThat(outboxCount(orderId)).isEqualTo(1);
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("재확인: 잔액 부족이면 PAYMENT_FAILED로 반영하고, 다시 결제하면 새 키로 결제된다")
    void recheck_insufficientBalance_thenRepay() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        orderFacade.payOrder(buyerId, orderId);
        String firstKey = find(orderId).getPaymentIdempotencyKey();
        movePayingStartedAt(orderId, LocalDateTime.now().minusMinutes(1));

        willReturn(new PayResultDto(null, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 5_000, 10_000)).given(paymentApi).pay(any());
        orderFacade.recheckPayingOrder(orderId, LocalDateTime.now());
        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);

        willReturn(PAID).given(paymentApi).pay(any());
        OrderPayResponse response = orderFacade.payOrder(buyerId, orderId);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(find(orderId).getPaymentIdempotencyKey()).isNotEqualTo(firstKey);
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("재확인: 결제 서비스가 만료로 판단하면 PAYMENT_FAILED로 두고, 만료 스케줄러가 취소하고 재고를 복구한다")
    void recheck_orderExpired_thenExpireCancels() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        orderFacade.payOrder(buyerId, orderId);
        movePayingStartedAt(orderId, LocalDateTime.now().minusMinutes(20));
        moveExpiresAt(orderId, LocalDateTime.now().minusMinutes(5));

        willReturn(new PayResultDto(null, PayResultStatus.FAILED_ORDER_EXPIRED, 0, 0)).given(paymentApi).pay(any());
        orderFacade.recheckPayingOrder(orderId, LocalDateTime.now());
        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);

        assertThat(orderFacade.expireOrder(orderId, LocalDateTime.now())).isTrue();
        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(productApi).restoreStock(any(), any());
    }

    @Test
    @DisplayName("만료 시각이 지난 뒤 반영되는 PAID 결과도 PAID로 반영한다 (payment가 만료 전 결제로 판단)")
    void paidAfterExpiresAt_isPaid() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        orderFacade.payOrder(buyerId, orderId);
        movePayingStartedAt(orderId, LocalDateTime.now().minusMinutes(20));
        moveExpiresAt(orderId, LocalDateTime.now().minusMinutes(5));

        willReturn(PAID).given(paymentApi).pay(any());
        orderFacade.recheckPayingOrder(orderId, LocalDateTime.now());

        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        // 만료 스케줄러 대상도 아니다
        assertThat(orderFacade.expireOrder(orderId, LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("결과 반영이 겹쳐도(재확인 두 번 동시) PAID 반영과 OrderPaidEvent는 한 번이고 결제 취소는 부르지 않는다")
    void concurrentApply_once() throws Exception {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        orderFacade.payOrder(buyerId, orderId);
        movePayingStartedAt(orderId, LocalDateTime.now().minusMinutes(1));
        willReturn(PAID).given(paymentApi).pay(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<Boolean>> futures = List.of(
                    CompletableFuture.supplyAsync(() -> { await(start); return orderFacade.recheckPayingOrder(orderId, LocalDateTime.now()); }, executor),
                    CompletableFuture.supplyAsync(() -> { await(start); return orderFacade.recheckPayingOrder(orderId, LocalDateTime.now()); }, executor));
            start.countDown();
            for (CompletableFuture<Boolean> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        verify(paymentApi, never()).cancelPayment(any());
    }

    @Test
    @DisplayName("PAYING 중에는 재결제·취소가 409-PAYMENT_IN_PROGRESS이고, 만료 처리·판매 중지 취소 대상이 아니다")
    void paying_guards() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        orderFacade.payOrder(buyerId, orderId);
        moveExpiresAt(orderId, LocalDateTime.now().minusMinutes(5));

        assertThatThrownBy(() -> orderFacade.payOrder(buyerId, orderId))
                .extracting("resultCode").isEqualTo("409-PAYMENT_IN_PROGRESS");
        assertThatThrownBy(() -> orderFacade.cancelOrder(buyerId, orderId))
                .extracting("resultCode").isEqualTo("409-PAYMENT_IN_PROGRESS");
        assertThat(orderFacade.findExpireTargetIds(LocalDateTime.now(), orderId - 1, 10)).doesNotContain(orderId);
        assertThat(orderFacade.expireOrder(orderId, LocalDateTime.now())).isFalse();
        assertThat(orderFacade.cancelPendingOrders(List.of(productId), OrderCancelType.PRODUCT_STOPPED)).isZero();

        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.PAYING);
        verify(paymentApi, times(1)).pay(any());
        verify(productApi, never()).restoreStock(any(), any());
    }

    @Test
    @DisplayName("회원 탈퇴 검사: PAYING 주문은 진행 중 주문으로 잡힌다")
    void paying_isActiveOrder() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        orderFacade.payOrder(buyerId, orderId);

        assertThat(orderFacade.hasActiveOrders(buyerId)).isTrue();
    }

    @Test
    @DisplayName("결제 서비스가 요청을 거절(4xx)하면 PAYMENT_FAILED로 커밋되고 같은 오류를 준다")
    void rejected_commitsPaymentFailed() {
        long orderId = saveCreatedOrder();
        given(paymentApi.pay(any())).willThrow(new DomainException("404-WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", null));

        assertThatThrownBy(() -> orderFacade.payOrder(buyerId, orderId))
                .extracting("resultCode").isEqualTo("404-WALLET_NOT_FOUND");
        assertThat(find(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(paymentApi, never()).cancelPayment(any());
    }

    // ---------- 도우미 ----------

    private long saveCreatedOrder() {
        Long id = transactionTemplate.execute(status -> orderRepository.save(Order.create(buyerId, null,
                List.of(new OrderItemSpec(null, productId, optionId, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                ShippingAddress.of("김결제", "01012345678", "06236", "서울 강남구 테헤란로 1", "101호", null),
                LocalDateTime.now(), 15)).getId());
        orderIds.add(id);
        return id;
    }

    private Order find(long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private void movePayingStartedAt(long orderId, LocalDateTime startedAt) {
        jdbcTemplate.update("update market_order set paying_started_at = ? where id = ?", startedAt, orderId);
    }

    private void moveExpiresAt(long orderId, LocalDateTime expiresAt) {
        jdbcTemplate.update("update market_order set expires_at = ? where id = ?", expiresAt, orderId);
    }

    private int outboxCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ? and event_type = 'OrderPaidEvent'",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
