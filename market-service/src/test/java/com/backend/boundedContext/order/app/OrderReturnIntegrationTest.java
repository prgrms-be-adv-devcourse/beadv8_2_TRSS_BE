package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.ReturnRequestResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.out.ProductApi;

/**
 * 반품 요청을 실제 DB·실제 트랜잭션·행 잠금으로 확인한다 (TRSS-222 구매자 반품 요청).
 * - 요청 성공: 상태·사유 코드 컬럼(문자열)·상태 이력 1행, outbox 없음
 * - 남의 주문 404, 두 번째 요청 409, 배송 완료 7일 지난 PAID 주문 200
 * - RETURN_REQUESTED 주문은 자동 확정 대상 조회·처리에서 빠진다(회귀)
 * - 반품 요청과 자동·수동 구매 확정이 동시에 와도 둘 중 하나만 반영된다(행 잠금)
 * - 로그에 배송지·연락처·상세 사유가 나오지 않는다
 * 회원·결제·상품은 다른 서비스라 목으로 둔다(checkActiveMember는 아무것도 하지 않음 = 활성 회원).
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class OrderReturnIntegrationTest {

    private static final String RECEIVER = "김반품수령";
    private static final String PHONE = "01055556666";
    private static final String ADDRESS = "서울 강남구 반품로 33";
    private static final String DETAIL = "반품동 303호";
    private static final String FREE_TEXT_REASON = "통합테스트 상세 사유 문장";

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private MemberApi memberApi;
    @MockitoBean private ProductApi productApi;

    private final long buyerId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final long optionId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    // ---------- 요청 ----------

    @Test
    @DisplayName("PAID + 배송 완료: RETURN_REQUESTED, 사유 코드는 이름 문자열로 저장, 상태 이력 1행, outbox 없음")
    void request_success() {
        long orderId = saveDeliveredOrder(LocalDateTime.now().minusDays(1));

        ReturnRequestResponse response = orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.DEFECTIVE, FREE_TEXT_REASON);

        assertThat(response.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(response.returnRequestedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForMap(
                "select status, return_reason_code, return_reason, return_requested_at from market_order where id = ?", orderId))
                .containsEntry("status", "RETURN_REQUESTED")
                .containsEntry("return_reason_code", "DEFECTIVE")
                .containsEntry("return_reason", FREE_TEXT_REASON);
        assertThat(jdbcTemplate.queryForList(
                "select from_status, reason from order_status_history where order_id = ? and to_status = 'RETURN_REQUESTED'", orderId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("from_status", "PAID");
                    assertThat(row).containsEntry("reason", "DEFECTIVE");
                });
        assertThat(outboxCount(orderId)).isZero();   // 요청은 이벤트를 발행하지 않는다

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.getReturnReasonCode()).isEqualTo(ReturnReasonCode.DEFECTIVE);   // 문자열 컬럼 → enum 읽기
    }

    @Test
    @DisplayName("다른 회원의 주문이면 404 ORDER_NOT_FOUND, 주문은 PAID 그대로")
    void request_otherBuyer_404() {
        long orderId = saveDeliveredOrder(LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> orderFacade.requestReturn(buyerId + 1, orderId, ReturnReasonCode.DEFECTIVE, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND.resultCode());
        assertThat(status(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("두 번째 요청은 409 RETURN_ALREADY_REQUESTED, 이력은 1행 그대로")
    void request_twice_409() {
        long orderId = saveDeliveredOrder(LocalDateTime.now().minusDays(1));
        orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.DEFECTIVE, null);

        assertThatThrownBy(() -> orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.CHANGE_OF_MIND, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(OrderErrorCode.RETURN_ALREADY_REQUESTED.resultCode());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from order_status_history where order_id = ? and to_status = 'RETURN_REQUESTED'",
                Long.class, orderId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("배송 완료 8일이 지났어도 자동 확정 전(PAID)이면 요청된다")
    void request_afterSevenDays_200() {
        long orderId = saveDeliveredOrder(LocalDateTime.now().minusDays(8));

        orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.CHANGE_OF_MIND, null);

        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);
    }

    // ---------- 자동 확정 회귀 ----------

    @Test
    @DisplayName("RETURN_REQUESTED 주문은 기준일이 지나도 자동 확정 대상 조회에 없고, 한 건 처리도 건너뛴다")
    void autoConfirm_skipsReturnRequested() {
        long orderId = saveDeliveredOrder(LocalDateTime.now().minusDays(8));
        orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.DEFECTIVE, null);
        LocalDateTime now = LocalDateTime.now();

        assertThat(orderFacade.findAutoConfirmTargetIds(now, orderId - 1, 100)).doesNotContain(orderId);
        assertThat(orderFacade.autoConfirmOrder(orderId, now)).isFalse();
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(outboxCount(orderId)).isZero();
    }

    // ---------- 동시성 ----------

    @Test
    @DisplayName("반품 요청과 자동 구매 확정이 동시에 와도 둘 중 하나만 반영된다 (요청 먼저: 확정 건너뜀 / 확정 먼저: 409 ORDER_STATE_INVALID)")
    void requestAndAutoConfirm_concurrently() throws Exception {
        raceWithConfirm(orderId -> () -> orderFacade.autoConfirmOrder(orderId, LocalDateTime.now()));
    }

    @Test
    @DisplayName("반품 요청과 수동 구매 확정이 동시에 와도 둘 중 하나만 반영된다 (요청 먼저: 409 ORDER_NOT_CONFIRMABLE)")
    void requestAndManualConfirm_concurrently() throws Exception {
        raceWithConfirm(orderId -> () -> {
            try {
                orderFacade.confirmOrder(buyerId, orderId);
                return true;
            } catch (DomainException e) {
                assertThat(e.getResultCode()).isEqualTo(OrderErrorCode.ORDER_NOT_CONFIRMABLE.resultCode());
                return false;
            }
        });
    }

    /**
     * 같은 주문에 반품 요청과 구매 확정을 동시에 보낸다. confirmFactory는 확정이 반영됐으면 true를 돌려주는 작업을 만든다.
     * 어느 순서든 잠금·버전 충돌 예외 없이 정확히 한쪽만 반영되고, 확정 이벤트는 확정됐을 때만 1건이다.
     */
    private void raceWithConfirm(java.util.function.LongFunction<Supplier<Boolean>> confirmFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int returnedFirst = 0;
        int confirmedFirst = 0;
        try {
            for (int round = 0; round < 10; round++) {
                long orderId = saveDeliveredOrder(LocalDateTime.now().minusDays(8));   // 자동 확정 대상이면서 반품 요청 가능
                CountDownLatch start = new CountDownLatch(1);

                CompletableFuture<String> request = CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    try {
                        orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.DEFECTIVE, null);
                        return "REQUESTED";
                    } catch (DomainException e) {
                        return e.getResultCode();
                    }
                }, executor);
                Supplier<Boolean> confirmTask = confirmFactory.apply(orderId);
                CompletableFuture<Boolean> confirm = CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    return confirmTask.get();
                }, executor);
                start.countDown();

                String requestResult = request.get(20, TimeUnit.SECONDS);
                boolean confirmed = confirm.get(20, TimeUnit.SECONDS);

                Order order = orderRepository.findById(orderId).orElseThrow();
                if (confirmed) {
                    confirmedFirst++;
                    assertThat(requestResult).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(order.getReturnRequestedAt()).isNull();
                    assertThat(outboxCount(orderId)).isEqualTo(1);   // OrderConfirmedEvent 1건
                } else {
                    returnedFirst++;
                    assertThat(requestResult).isEqualTo("REQUESTED");
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
                    assertThat(order.getConfirmedAt()).isNull();
                    assertThat(outboxCount(orderId)).isZero();
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(returnedFirst + confirmedFirst).isEqualTo(10);
    }

    // ---------- 로그 ----------

    @Test
    @DisplayName("반품 요청 로그에는 주문 id·사유 코드만 있고 배송지·연락처·상세 사유는 없다")
    void logs_haveNoPersonalData(CapturedOutput output) {
        long orderId = saveDeliveredOrder(LocalDateTime.now().minusDays(1));

        orderFacade.requestReturn(buyerId, orderId, ReturnReasonCode.OTHER, FREE_TEXT_REASON);

        assertThat(output).contains("반품 요청: orderId=" + orderId + ", reasonCode=OTHER");
        assertThat(output.getAll()).doesNotContain(RECEIVER, PHONE, ADDRESS, DETAIL, FREE_TEXT_REASON);
    }

    // ---------- 도우미 ----------

    /** 배송지 있는 주문을 결제하고 deliveredAt에 배송 완료한 뒤 저장 */
    private long saveDeliveredOrder(LocalDateTime deliveredAt) {
        Long id = transactionTemplate.execute(status -> {
            LocalDateTime paidAt = deliveredAt.minusDays(1);
            Order order = Order.create(buyerId, null,
                    List.of(new OrderItemSpec(null, 10L, optionId, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                    ShippingAddress.of(RECEIVER, PHONE, "06236", ADDRESS, DETAIL, "문 앞"),
                    paidAt.minusMinutes(1), 15);
            order.pay(paidAt);
            order.markDelivered(deliveredAt);
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

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
