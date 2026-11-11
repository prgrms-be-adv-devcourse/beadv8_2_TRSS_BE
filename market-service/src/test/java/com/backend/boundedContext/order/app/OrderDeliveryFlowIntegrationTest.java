package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;

/**
 * [QA] PR 4 배송 흐름을 실제 DB(로컬 Docker PostgreSQL)·실제 트랜잭션·실제 EventPublisher(outbox)로 확인한다.
 * - 결제 성공 + 배송지 → outbox에 OrderPaidEvent 1행, 배송지 없음·잔액 부족 → 0행(롤백)
 * - delivery가 죽어 있으면(실제 DeliveryApiClient가 닫힌 포트로 접속) 주문 상세는 예외 없이 deliveryUnavailable = true
 * - DB에서 다시 읽은 주문으로 구매 확정: 배송지 있음 + 미배송 → 409 ORDER_NOT_DELIVERED, 배송지 없음 → 지금처럼 확정
 * - 수동 구매 확정과 배송 완료 반영이 동시에 와도 예외(잠금·버전 충돌) 없이 결과 하나
 * - 이 흐름의 로그에 배송지(수령인·전화·주소)가 나오지 않는다
 * 결제·회원·상품 조회는 다른 서비스(또는 다른 컨텍스트)라 목으로 둔다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        // delivery 서비스가 죽은 상황: 아무도 듣지 않는 포트
        "custom.delivery.baseUrl=http://127.0.0.1:1",
        "custom.delivery.connectTimeoutMillis=500",
        "custom.delivery.readTimeoutMillis=500"
})
@ExtendWith(OutputCaptureExtension.class)
class OrderDeliveryFlowIntegrationTest {

    private static final String RECEIVER = "김배송수령";
    private static final String PHONE = "01098765432";
    private static final String ADDRESS = "서울 강남구 큐에이로 77";
    private static final String DETAIL = "QA동 707호";

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

    @BeforeEach
    void setUp() {
        given(productApi.getOptions(anyCollection())).willReturn(List.of(
                new OptionSnapshotDto(optionId, 10L, 2L, "립스틱", "기본", 10_000, 10, true, null, null)));
    }

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    // ---------- OrderPaidEvent 발행 (outbox 실제 저장) ----------

    @Test
    @DisplayName("결제 성공 + 배송지: outbox에 OrderPaidEvent가 정확히 1행 저장되고 배송지·항목이 담긴다")
    void pay_withAddress_savesOneOrderPaidEvent() {
        long orderId = saveCreatedOrder(true);
        given(paymentApi.pay(any())).willReturn(new PayResultDto(900L, PayResultStatus.PAID, 0, 0));

        orderFacade.payOrder(buyerId, orderId);

        List<Map<String, Object>> rows = outboxRows(orderId, "OrderPaidEvent");
        assertThat(rows).hasSize(1);
        String payload = String.valueOf(rows.get(0).get("payload"));
        assertThat(payload).contains(RECEIVER, PHONE, "06236", ADDRESS, "립스틱", String.valueOf(optionId));
        assertThat(status(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("결제 성공 + 배송지 없음(지금 프론트): OrderPaidEvent를 저장하지 않는다")
    void pay_withoutAddress_savesNothing() {
        long orderId = saveCreatedOrder(false);
        given(paymentApi.pay(any())).willReturn(new PayResultDto(901L, PayResultStatus.PAID, 0, 0));

        orderFacade.payOrder(buyerId, orderId);

        assertThat(outboxRows(orderId, "OrderPaidEvent")).isEmpty();
        assertThat(status(orderId)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("결제 실패(잔액 부족) + 배송지: 402, 주문은 결제 전으로 롤백되고 OrderPaidEvent는 0행")
    void pay_insufficientBalance_rollsBack() {
        long orderId = saveCreatedOrder(true);
        given(paymentApi.pay(any())).willReturn(
                new PayResultDto(null, PayResultStatus.FAILED_INSUFFICIENT_BALANCE, 5_000, 10_000));

        assertThatThrownBy(() -> orderFacade.payOrder(buyerId, orderId))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(OrderErrorCode.INSUFFICIENT_BALANCE.resultCode());

        assertThat(outboxRows(orderId, "OrderPaidEvent")).isEmpty();
        assertThat(status(orderId)).isEqualTo(OrderStatus.CREATED);
    }

    // ---------- 주문 상세: delivery 장애 격리 ----------

    @Test
    @DisplayName("delivery가 죽어 있어도 주문 상세는 예외 없이 응답하고 deliveryUnavailable = true, 배송지는 그대로 준다")
    void detail_deliveryDown_unavailable(CapturedOutput output) {
        long orderId = savePaidOrder(true, null);

        OrderDetailResponse detail = orderFacade.getOrderDetail(buyerId, orderId);

        assertThat(detail.deliveryUnavailable()).isTrue();
        assertThat(detail.delivery()).isNull();
        assertThat(detail.shippingAddress().receiverName()).isEqualTo(RECEIVER);
        assertThat(output).contains("배송 정보 조회에 실패해 주문 상세를 배송 정보 없이 응답합니다: orderId=" + orderId);
        assertNoShippingAddressInLogs(output);
    }

    @Test
    @DisplayName("배송지 없는 주문은 delivery가 죽어 있어도 부르지 않으므로 deliveryUnavailable = false")
    void detail_noAddress_doesNotCallDelivery(CapturedOutput output) {
        long orderId = savePaidOrder(false, null);

        OrderDetailResponse detail = orderFacade.getOrderDetail(buyerId, orderId);

        assertThat(detail.deliveryUnavailable()).isFalse();
        assertThat(detail.shippingAddress()).isNull();
        assertThat(output).doesNotContain("배송 정보 조회에 실패해 주문 상세를 배송 정보 없이 응답합니다: orderId=" + orderId);
    }

    // ---------- 구매 확정 (DB에서 다시 읽은 주문) ----------

    @Test
    @DisplayName("배송지 있음 + 미배송 주문을 확정하면 409 ORDER_NOT_DELIVERED, 주문은 PAID로 남고 OrderConfirmedEvent 없음")
    void confirm_notDelivered_409() {
        long orderId = savePaidOrder(true, null);

        assertThatThrownBy(() -> orderFacade.confirmOrder(buyerId, orderId))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(OrderErrorCode.ORDER_NOT_DELIVERED.resultCode());

        assertThat(status(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(outboxRows(orderId, "OrderConfirmedEvent")).isEmpty();
    }

    @Test
    @DisplayName("배송지 없는 주문(배송지 컬럼 모두 null로 저장)은 다시 읽어도 배송지 없음으로 보고 지금처럼 확정된다")
    void confirm_noAddress_confirmsLikeBefore() {
        long orderId = savePaidOrder(false, null);

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.hasShippingAddress()).isFalse();

        orderFacade.confirmOrder(buyerId, orderId);

        assertThat(status(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(outboxRows(orderId, "OrderConfirmedEvent")).hasSize(1);
    }

    @Test
    @DisplayName("배송 완료가 반영된 뒤(컨슈머 경로)에는 수동 확정이 된다")
    void confirm_afterMarkDelivered() {
        long orderId = savePaidOrder(true, null);

        orderFacade.markDelivered(orderId, LocalDateTime.now());
        orderFacade.confirmOrder(buyerId, orderId);

        assertThat(status(orderId)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("수동 확정과 배송 완료 반영이 동시에 와도 잠금·버전 충돌 없이 끝난다 (확정이 먼저면 409 NOT_DELIVERED, 뒤면 확정)")
    void manualConfirmAndMarkDelivered_concurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                long orderId = savePaidOrder(true, null);
                LocalDateTime deliveredAt = LocalDateTime.of(2026, 11, 7, 10, 0);
                CountDownLatch start = new CountDownLatch(1);

                CompletableFuture<Void> mark = CompletableFuture.runAsync(() -> {
                    awaitLatch(start);
                    orderFacade.markDelivered(orderId, deliveredAt);
                }, executor);
                CompletableFuture<Object> confirm = CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    try {
                        orderFacade.confirmOrder(buyerId, orderId);
                        return "CONFIRMED";
                    } catch (DomainException e) {
                        return e.getResultCode();
                    }
                }, executor);
                start.countDown();

                mark.get(20, TimeUnit.SECONDS);   // 배송 완료 반영은 어떤 순서에서도 예외가 없어야 한다
                Object confirmResult = confirm.get(20, TimeUnit.SECONDS);

                Order order = orderRepository.findById(orderId).orElseThrow();
                assertThat(order.getDeliveredAt()).isEqualTo(deliveredAt);
                if ("CONFIRMED".equals(confirmResult)) {
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                } else {
                    assertThat(confirmResult).isEqualTo(OrderErrorCode.ORDER_NOT_DELIVERED.resultCode());
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------- 로그에 배송지 없음 ----------

    @Test
    @DisplayName("결제·배송 완료 반영·확정 실패 흐름의 로그에 수령인·전화번호·주소가 나오지 않는다")
    void flowLogs_haveNoShippingAddress(CapturedOutput output) {
        long orderId = saveCreatedOrder(true);
        given(paymentApi.pay(any())).willReturn(new PayResultDto(902L, PayResultStatus.PAID, 0, 0));

        orderFacade.payOrder(buyerId, orderId);
        assertThatThrownBy(() -> orderFacade.confirmOrder(buyerId, orderId)).isInstanceOf(DomainException.class);
        orderFacade.markDelivered(orderId, LocalDateTime.now());
        orderFacade.getOrderDetail(buyerId, orderId);

        assertThat(output).contains("배송 완료 반영: orderId=" + orderId);
        assertNoShippingAddressInLogs(output);
    }

    // ---------- 도우미 ----------

    private static void assertNoShippingAddressInLogs(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(RECEIVER, PHONE, ADDRESS, DETAIL);
    }

    private long saveCreatedOrder(boolean withAddress) {
        Long id = transactionTemplate.execute(status -> orderRepository.save(newOrder(withAddress, LocalDateTime.now())).getId());
        orderIds.add(id);
        return id;
    }

    private long savePaidOrder(boolean withAddress, LocalDateTime deliveredAt) {
        Long id = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            Order order = newOrder(withAddress, now.minusMinutes(1));
            order.pay(now);
            if (deliveredAt != null) {
                order.markDelivered(deliveredAt);
            }
            return orderRepository.save(order).getId();
        });
        orderIds.add(id);
        return id;
    }

    private Order newOrder(boolean withAddress, LocalDateTime createdAt) {
        return Order.create(buyerId, null,
                List.of(new OrderItemSpec(null, 10L, optionId, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                withAddress ? ShippingAddress.of(RECEIVER, PHONE, "06236", ADDRESS, DETAIL, "문 앞") : null,
                createdAt, 15);
    }

    private List<Map<String, Object>> outboxRows(long orderId, String eventType) {
        return jdbcTemplate.queryForList(
                "select event_type, payload::text as payload from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ? and event_type = ?",
                orderId, eventType);
    }

    private OrderStatus status(long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
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
