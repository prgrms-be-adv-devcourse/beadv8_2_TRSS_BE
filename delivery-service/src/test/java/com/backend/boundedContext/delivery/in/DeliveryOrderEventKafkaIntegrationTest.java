package com.backend.boundedContext.delivery.in;

import static com.backend.boundedContext.delivery.DeliveryFixtures.item;
import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static com.backend.boundedContext.delivery.DeliveryFixtures.withAddress;
import static com.backend.boundedContext.delivery.DeliveryFixtures.withItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.event.OrderConfirmedEvent;
import com.backend.shared.order.event.OrderPaidEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * 주문 서비스가 order.events로 보낸 결제 완료 이벤트를 delivery 컨슈머가 받아 배송을 만드는지 확인한다.
 * Kafka는 EmbeddedKafka(테스트 JVM 안의 브로커), DB는 로컬 Docker PostgreSQL(delivery 스키마)을 쓴다.
 * market의 outbox 대신 릴레이(OutboxKafkaSender)와 같은 형식(봉투 JSON + eventType 헤더, 키 orderId)으로 직접 보낸다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
// 컨슈머가 구독하기 전에 토픽을 운영과 같은 파티션 수(3)로 만들어 둔다
@EmbeddedKafka(topics = {"order.events", "order.events-dlt"}, partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DeliveryOrderEventKafkaIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EventMessages eventMessages;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    // 다른 테스트·로컬 데이터와 겹치지 않는 주문 ID (주문 항목 ID는 orderId * 10 + n)
    private final long orderId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);

    /** 테스트가 보낸 이벤트 ID (처리 기록 정리용) */
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from delivery_item where delivery_id in (select id from delivery where order_id = ?)", orderId);
        jdbcTemplate.update("delete from delivery where order_id = ?", orderId);
        eventIds.forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
    }

    @Test
    @DisplayName("판매자 2명·항목 3개 결제 완료 이벤트 → 배송 1건(READY), 항목 3개, 처리 기록 1행")
    void orderPaid_createsOneDelivery() {
        UUID eventId = send(paidEvent(orderId, orderId * 10));

        await().atMost(WAIT).until(() -> processed(eventId));
        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from delivery where order_id = ?", String.class, orderId))
                .isEqualTo("READY");
        assertThat(itemCount()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "select count(distinct seller_id) from delivery_item where delivery_id = (select id from delivery where order_id = ?)",
                Integer.class, orderId)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 eventId가 두 번 오면 배송 1건, processed_event(eventId, delivery) 1행")
    void sameEventTwice_handledOnce() {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(paidEvent(orderId, orderId * 10));
        send(envelope, true);
        send(envelope, true);
        // 같은 키(orderId)로 뒤에 보낸 이벤트가 처리되면 앞의 중복 메시지도 이미 받은 것이다
        UUID marker = send(paidEvent(orderId, orderId * 10 + 5));

        await().atMost(WAIT).until(() -> processed(marker));
        assertThat(processedCount(envelope.eventId())).isEqualTo(1);
        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("eventId가 다른 같은 주문 이벤트가 두 번 오면 배송 1건 (두 번째는 건너뜀)")
    void sameOrderDifferentEvent_oneDelivery() {
        UUID first = send(paidEvent(orderId, orderId * 10));
        UUID second = send(paidEvent(orderId, orderId * 10));

        await().atMost(WAIT).until(() -> processed(first) && processed(second));
        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("eventType 헤더가 OrderConfirmedEvent면 무시하고 처리 기록을 남기지 않는다")
    void confirmedEventWithHeader_ignored() {
        UUID ignored = send(confirmedEvent(), true);
        UUID marker = send(paidEvent(orderId, orderId * 10));

        await().atMost(WAIT).until(() -> processed(marker));
        assertThat(processedCount(ignored)).isZero();
        assertThat(deliveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("헤더 없는 OrderConfirmedEvent도 봉투를 읽어 무시한다")
    void confirmedEventWithoutHeader_ignored() {
        UUID ignored = send(confirmedEvent(), false);
        UUID marker = send(paidEvent(orderId, orderId * 10));

        await().atMost(WAIT).until(() -> processed(marker));
        assertThat(processedCount(ignored)).isZero();
        assertThat(deliveryCount()).isEqualTo(1);
    }

    static Stream<Arguments> invalidEvents() {
        return Stream.of(
                Arguments.of("배송지 null", (LongFunction<OrderPaidEvent>) id -> withAddress(paidEvent(id, id * 10), null)),
                Arguments.of("항목 빈 목록", (LongFunction<OrderPaidEvent>) id -> withItems(paidEvent(id, id * 10), List.of())),
                Arguments.of("수량 0", (LongFunction<OrderPaidEvent>) id -> withItems(paidEvent(id, id * 10), List.of(item(id * 10, 1L, 0)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvents")
    @DisplayName("배송을 만들 수 없는 결제 완료 이벤트는 재시도 없이 order.events-dlt로 가고 배송·처리 기록이 남지 않는다")
    void invalidEvent_goesToDlt(String name, LongFunction<OrderPaidEvent> event) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "dlt-check-" + UUID.randomUUID(), true);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "order.events-dlt");
            UUID eventId = send(event.apply(orderId));

            ConsumerRecord<String, String> dead = null;
            long deadline = System.currentTimeMillis() + WAIT.toMillis();
            while (dead == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                    if (String.valueOf(orderId).equals(r.key())) dead = r;
                }
            }
            assertThat(dead).as("order.events-dlt에 도착해야 한다").isNotNull();
            assertThat(processedCount(eventId)).isZero();   // 처리 기록도 롤백됨
            assertThat(deliveryCount()).isZero();
        }
    }

    private OrderConfirmedEvent confirmedEvent() {
        return new OrderConfirmedEvent(orderId, 77L, LocalDateTime.now(), ConfirmType.MANUAL, List.of());
    }

    private UUID send(DomainEvent event) {
        return send(EventEnvelope.wrap(event), true);
    }

    private UUID send(DomainEvent event, boolean withHeader) {
        return send(EventEnvelope.wrap(event), withHeader);
    }

    // 릴레이(OutboxKafkaSender)와 같은 형식으로 보낸다. withHeader가 false면 DLT 재전송처럼 헤더 없이 보낸다
    private UUID send(EventEnvelope<DomainEvent> envelope, boolean withHeader) {
        OutboxEvent outbox = OutboxEvent.create(envelope, objectMapper.writeValueAsString(envelope.payload()));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "order.events", String.valueOf(orderId), eventMessages.toJson(outbox));
        if (withHeader) {
            record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record).join();
        eventIds.add(envelope.eventId());
        return envelope.eventId();
    }

    private boolean processed(UUID eventId) {
        return processedCount(eventId) == 1;
    }

    private int processedCount(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'delivery'", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int deliveryCount() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from delivery where order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private int itemCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from delivery_item where delivery_id in (select id from delivery where order_id = ?)",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }
}
