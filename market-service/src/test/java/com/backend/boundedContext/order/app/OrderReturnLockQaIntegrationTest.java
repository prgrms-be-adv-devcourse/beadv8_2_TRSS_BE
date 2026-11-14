package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.out.ProductApi;

/**
 * [QA] 반품 요청 경합을 순서를 고정해 재현한다 (TRSS-222 구매자 반품 요청).
 * 한 트랜잭션이 주문 행 잠금을 쥔 채 멈춰 있는 동안 다른 쪽이 실제로 기다리는지(블로킹) 확인하고,
 * 잠금이 풀린 뒤 기다리던 쪽이 커밋된 최신 상태를 다시 보고 판단하는지 확인한다.
 * - 자동 확정이 먼저 잠금 → 반품 요청은 기다렸다가 409 ORDER_STATE_INVALID
 * - 반품 요청이 먼저 잠금 → 자동 확정은 기다렸다가 건너뜀(false), 수동 확정은 409 ORDER_NOT_CONFIRMABLE
 * - 배송 완료 반영이 먼저 잠금 → 반품 요청은 기다렸다가 성공(배송 완료를 본다)
 * 잠금 없이 읽으면 기다리지 않거나(블로킹 검사 실패) @Version 충돌(낙관적 락 예외)이 난다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderReturnLockQaIntegrationTest {

    private static final long BLOCK_CHECK_MILLIS = 700;

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
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    @Test
    @DisplayName("자동 확정이 잠금을 쥔 동안 반품 요청은 기다리고, 확정 커밋 뒤 409 ORDER_STATE_INVALID")
    void autoConfirmHoldsLock_requestWaitsThen409() throws Exception {
        long orderId = saveOrder(LocalDateTime.now().minusDays(8));

        String result = runWhileHolding(
                () -> orderFacade.autoConfirmOrder(orderId, LocalDateTime.now()),
                () -> requestResult(orderId));

        assertThat(result).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReturnRequestedAt()).isNull();
        assertThat(outboxCount(orderId)).isEqualTo(1);   // OrderConfirmedEvent만
    }

    @Test
    @DisplayName("반품 요청이 잠금을 쥔 동안 자동 확정은 기다리고, 요청 커밋 뒤 건너뛴다(false, outbox 없음)")
    void requestHoldsLock_autoConfirmWaitsThenSkips() throws Exception {
        long orderId = saveOrder(LocalDateTime.now().minusDays(8));

        Boolean confirmed = runWhileHolding(
                () -> orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.DEFECTIVE, null),
                () -> orderFacade.autoConfirmOrder(orderId, LocalDateTime.now()));

        assertThat(confirmed).isFalse();
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(outboxCount(orderId)).isZero();
    }

    @Test
    @DisplayName("반품 요청이 잠금을 쥔 동안 수동 확정은 기다리고, 요청 커밋 뒤 409 ORDER_NOT_CONFIRMABLE")
    void requestHoldsLock_manualConfirmWaitsThen409() throws Exception {
        long orderId = saveOrder(LocalDateTime.now().minusDays(2));

        String result = runWhileHolding(
                () -> orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.DEFECTIVE, null),
                () -> {
                    try {
                        orderFacade.confirmOrder(buyerId, orderId);
                        return "CONFIRMED";
                    } catch (DomainException e) {
                        return e.getResultCode();
                    }
                });

        assertThat(result).isEqualTo(OrderErrorCode.ORDER_NOT_CONFIRMABLE.resultCode());
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(outboxCount(orderId)).isZero();
    }

    @Test
    @DisplayName("수동 확정이 잠금을 쥔 동안 반품 요청은 기다리고, 확정 커밋 뒤 409 ORDER_STATE_INVALID")
    void manualConfirmHoldsLock_requestWaitsThen409() throws Exception {
        long orderId = saveOrder(LocalDateTime.now().minusDays(2));

        String result = runWhileHolding(
                () -> orderFacade.confirmOrder(buyerId, orderId),
                () -> requestResult(orderId));

        assertThat(result).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
        assertThat(status(orderId)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("배송 완료 반영이 잠금을 쥔 동안 반품 요청은 기다리고, 반영 커밋 뒤 배송 완료를 보고 성공한다")
    void markDeliveredHoldsLock_requestWaitsThenSucceeds() throws Exception {
        long orderId = saveOrder(null);   // 결제만 됨, 미배송
        assertThat(requestResult(orderId)).isEqualTo(OrderErrorCode.ORDER_NOT_DELIVERED.resultCode());   // 반영 전 요청은 409

        LocalDateTime deliveredAt = LocalDateTime.now().minusHours(1);
        String result = runWhileHolding(
                () -> {
                    orderFacade.markDelivered(orderId, deliveredAt);
                    return null;
                },
                () -> requestResult(orderId));

        assertThat(result).isEqualTo("REQUESTED");
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.getDeliveredAt()).isNotNull();
    }

    // ---------- 도우미 ----------

    /**
     * holder를 트랜잭션 안에서 실행해 주문 행 잠금을 쥔 채 멈추고, 그동안 contender를 다른 스레드로 시작한다.
     * contender가 BLOCK_CHECK_MILLIS 동안 끝나지 않는지(잠금 대기) 확인한 뒤 holder를 커밋하고 contender 결과를 돌려준다.
     */
    private <T> T runWhileHolding(Supplier<?> holder, Supplier<T> contender) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holding = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    holder.get();   // Facade @Transactional은 바깥 트랜잭션에 참여 → 커밋은 release 뒤
                    locked.countDown();
                    await(release);
                }), executor);

        assertThat(locked.await(20, TimeUnit.SECONDS)).as("holder가 잠금을 잡음").isTrue();
        CompletableFuture<T> contending = CompletableFuture.supplyAsync(contender, executor);

        Thread.sleep(BLOCK_CHECK_MILLIS);
        assertThat(contending.isDone()).as("잠금을 쥔 동안 상대는 기다려야 한다").isFalse();

        release.countDown();
        holding.get(20, TimeUnit.SECONDS);
        return contending.get(20, TimeUnit.SECONDS);
    }

    private String requestResult(long orderId) {
        try {
            orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.DEFECTIVE, null);
            return "REQUESTED";
        } catch (DomainException e) {
            return e.getResultCode();
        }
    }

    /** 배송지 있는 주문을 결제하고, deliveredAt이 있으면 배송 완료까지 해서 저장 */
    private long saveOrder(LocalDateTime deliveredAt) {
        Long id = transactionTemplate.execute(status -> {
            LocalDateTime paidAt = (deliveredAt == null ? LocalDateTime.now() : deliveredAt).minusDays(1);
            Order order = Order.create(buyerId, null,
                    List.of(new OrderItemSpec(null, 10L, optionId, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                    ShippingAddress.of("김잠금", "01011112222", "06236", "서울 강남구 잠금로 1", "101호", "문 앞"),
                    paidAt.minusMinutes(1), 15);
            order.pay(paidAt);
            if (deliveredAt != null) {
                order.markDelivered(deliveredAt);
            }
            return orderRepository.save(order).getId();
        });
        orderIds.add(id);
        return id;
    }

    private OrderStatus status(long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private int outboxCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
