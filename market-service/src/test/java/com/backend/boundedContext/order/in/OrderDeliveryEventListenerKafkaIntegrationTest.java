package com.backend.boundedContext.order.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.delivery.event.DeliveryShippedEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.OrderStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * delivery 서비스가 delivery.events로 보낸 배송 완료 이벤트를 market 컨슈머가 받아 주문에 기록하는지 확인한다.
 * Kafka는 EmbeddedKafka(테스트 JVM 안의 브로커), DB는 로컬 Docker PostgreSQL을 쓴다.
 * delivery의 outbox 대신, 릴레이(OutboxKafkaSender)와 같은 형식(봉투 JSON + eventType 헤더, 키 deliveryId)으로 직접 보낸다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
// 컨슈머가 구독하기 전에 토픽을 운영과 같은 파티션 수(3)로 만들어 둔다. seller.events는 같은 컨텍스트의 다른 컨슈머용
@EmbeddedKafka(topics = {"delivery.events", "seller.events"}, partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ExtendWith(OutputCaptureExtension.class)
class OrderDeliveryEventListenerKafkaIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EventMessages eventMessages;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderFacade orderFacade;

    // 다른 테스트·시드와 겹치지 않는 구매자 ID
    private final long buyerId = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);

    private final List<Long> orderIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        eventIds.forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
    }

    @Test
    @DisplayName("배송 완료 이벤트를 받으면 주문의 배송 완료 시각이 기록된다")
    void completed_setsDeliveredAt() {
        long orderId = savePaidOrderWithAddress();
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 11, 3, 15, 0);

        UUID eventId = send(new DeliveryCompletedEvent(70L, orderId, 3L, deliveredAt));

        await().atMost(WAIT).until(() -> processed(eventId));
        assertThat(deliveredAt(orderId)).isEqualTo(deliveredAt);
    }

    @Test
    @DisplayName("같은 토픽의 배송 시작 이벤트는 무시한다: 처리 기록 없음, 주문 배송 완료 시각 그대로")
    void shippedEvent_ignored() {
        long orderId = savePaidOrderWithAddress();
        UUID shipped = send(new DeliveryShippedEvent(70L, orderId, buyerId, "CJ대한통운", "123456789012",
                LocalDateTime.of(2026, 11, 2, 10, 0)));
        // 같은 키(deliveryId)로 뒤에 보낸 배송 완료가 처리되면 앞의 배송 시작 메시지도 이미 받은 것이다
        UUID marker = send(new DeliveryCompletedEvent(70L, orderId, buyerId, LocalDateTime.of(2026, 11, 3, 15, 0)));

        await().atMost(WAIT).until(() -> processed(marker));
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ?", Integer.class, shipped);
        assertThat(count).isZero();
        assertThat(deliveredAt(orderId)).isEqualTo(LocalDateTime.of(2026, 11, 3, 15, 0));
    }

    @Test
    @DisplayName("같은 eventId가 두 번 오면 한 번만 처리한다")
    void duplicateMessage_handledOnce() {
        long orderId = savePaidOrderWithAddress();
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(
                new DeliveryCompletedEvent(70L, orderId, 3L, LocalDateTime.of(2026, 11, 3, 15, 0)));
        send(envelope);
        send(envelope);
        // 같은 키로 뒤에 보낸 이벤트가 처리되면 앞의 중복 메시지도 이미 받은 것이다
        UUID marker = send(new DeliveryCompletedEvent(70L, orderId, 3L, LocalDateTime.of(2026, 11, 9, 9, 0)));

        await().atMost(WAIT).until(() -> processed(marker));
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'market'", Integer.class, envelope.eventId());
        assertThat(count).isEqualTo(1);
        assertThat(deliveredAt(orderId)).isEqualTo(LocalDateTime.of(2026, 11, 3, 15, 0));
    }

    @Test
    @DisplayName("eventId가 다른 배송 완료가 같은 주문에 두 번 오면 처음 값을 유지한다")
    void twoEventsSameOrder_keepsFirst() {
        long orderId = savePaidOrderWithAddress();
        LocalDateTime first = LocalDateTime.of(2026, 11, 3, 15, 0);

        send(new DeliveryCompletedEvent(70L, orderId, 3L, first));
        UUID second = send(new DeliveryCompletedEvent(70L, orderId, 3L, first.plusDays(1)));

        await().atMost(WAIT).until(() -> processed(second));
        assertThat(deliveredAt(orderId)).isEqualTo(first);
    }

    @Test
    @DisplayName("없는 주문이면 WARN만 남기고 정상 처리로 끝낸다 (DLT로 보내지 않음: 처리 기록이 커밋된다)")
    void unknownOrder_warnsWithoutDlt(CapturedOutput output) {
        long unknownOrderId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);

        UUID eventId = send(new DeliveryCompletedEvent(71L, unknownOrderId, 3L, LocalDateTime.of(2026, 11, 3, 15, 0)));

        // 처리 중 예외가 나면 processed_event 기록도 롤백되므로, 기록이 남았다는 것은 재시도·DLT 없이 끝났다는 뜻이다
        await().atMost(WAIT).until(() -> processed(eventId));
        assertThat(output).contains("배송 완료 이벤트의 주문이 없습니다: orderId=" + unknownOrderId);
        assertThat(output).doesNotContain("dlt=delivery.events-dlt");
    }

    @Test
    @DisplayName("배송 완료 반영과 자동 확정이 동시에 와도 주문 행 잠금으로 차례로 처리돼 예외 없이 끝난다")
    void markDeliveredAndAutoConfirm_concurrently() throws Exception {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                // 배송 완료 8일 지난 주문 (자동 확정 대상). 배송 완료 이벤트가 늦게 한 번 더 온 상황
                LocalDateTime deliveredAt = now.minusDays(8);
                long orderId = savePaidOrderWithAddress(now.minusDays(10), deliveredAt);
                CountDownLatch start = new CountDownLatch(1);

                CompletableFuture<Void> mark = CompletableFuture.runAsync(() -> {
                    awaitLatch(start);
                    orderFacade.markDelivered(orderId, now.minusDays(1));
                }, executor);
                CompletableFuture<Boolean> confirm = CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    return orderFacade.autoConfirmOrder(orderId, now);
                }, executor);
                start.countDown();

                CompletableFuture.allOf(mark, confirm).get(20, TimeUnit.SECONDS);   // 예외가 나면 여기서 던진다

                assertThat(confirm.get()).isTrue();
                Order order = orderRepository.findById(orderId).orElseThrow();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(order.getDeliveredAt()).isEqualTo(deliveredAt);   // 처음 값 유지
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("배송 전 주문에 배송 완료와 자동 확정이 동시에 오면 예외 없이 끝나고, 확정은 배송 완료 7일 뒤로 미뤄진다")
    void markDeliveredAndAutoConfirm_beforeDelivery() throws Exception {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                long orderId = savePaidOrderWithAddress(now.minusDays(10), null);
                CountDownLatch start = new CountDownLatch(1);

                CompletableFuture<Void> mark = CompletableFuture.runAsync(() -> {
                    awaitLatch(start);
                    orderFacade.markDelivered(orderId, now);
                }, executor);
                CompletableFuture<Boolean> confirm = CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    return orderFacade.autoConfirmOrder(orderId, now);
                }, executor);
                start.countDown();

                CompletableFuture.allOf(mark, confirm).get(20, TimeUnit.SECONDS);

                // 어느 쪽이 먼저든 배송 완료가 방금이라 확정 대상이 아니다
                assertThat(confirm.get()).isFalse();
                Order order = orderRepository.findById(orderId).orElseThrow();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
                assertThat(order.getDeliveredAt()).isEqualTo(now);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long savePaidOrderWithAddress() {
        LocalDateTime now = LocalDateTime.now();
        return savePaidOrderWithAddress(now, null);
    }

    /** paidAt에 결제된 배송지 있는 주문. deliveredAt이 있으면 배송 완료도 기록한다 */
    private long savePaidOrderWithAddress(LocalDateTime paidAt, LocalDateTime deliveredAt) {
        Long id = transactionTemplate.execute(status -> {
            Order order = Order.create(buyerId, null,
                    List.of(new OrderItemSpec(null, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 1)),
                    ShippingAddress.of("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", null, null),
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

    private UUID send(DomainEvent event) {
        return send(EventEnvelope.wrap(event));
    }

    // 릴레이(OutboxKafkaSender)와 같은 형식으로 보낸다. 키는 aggregateId(deliveryId)
    private UUID send(EventEnvelope<DomainEvent> envelope) {
        OutboxEvent outbox = OutboxEvent.create(envelope, objectMapper.writeValueAsString(envelope.payload()));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "delivery.events", String.valueOf(envelope.payload().aggregateId()), eventMessages.toJson(outbox));
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
        eventIds.add(envelope.eventId());
        return envelope.eventId();
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'market'", Integer.class, eventId);
        return count != null && count == 1;
    }

    private LocalDateTime deliveredAt(long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getDeliveredAt();
    }
}
