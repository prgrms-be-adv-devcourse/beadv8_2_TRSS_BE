package com.backend.boundedContext.delivery.in;

import static com.backend.boundedContext.delivery.DeliveryFixtures.item;
import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static com.backend.boundedContext.delivery.DeliveryFixtures.withAddress;
import static com.backend.boundedContext.delivery.DeliveryFixtures.withItems;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import com.backend.shared.order.event.OrderPaidEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * (QA 추가) 배송을 만들 수 없는 결제 완료 이벤트가 "재시도 없이" DLT로 가는지 확인한다.
 * 첫 재시도 대기를 60초로 늘려 두고, DLT 도착이 15초 안이면 재시도하지 않은 것이다.
 * DLT 헤더의 예외 메시지·스택에 배송지 값(수령인·연락처·주소)이 들어가지 않는지도 본다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "custom.kafka.consumer.retryInitialIntervalMillis=60000"
})
@EmbeddedKafka(topics = {"order.events", "order.events-dlt"}, partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DeliveryOrderEventNoRetryKafkaIntegrationTest {

    private static final Duration NO_RETRY_LIMIT = Duration.ofSeconds(15);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EventMessages eventMessages;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    private final long orderId = ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from delivery_item where delivery_id in (select id from delivery where order_id = ?)", orderId);
        jdbcTemplate.update("delete from delivery where order_id = ?", orderId);
        eventIds.forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
    }

    @Test
    @DisplayName("배송지 null 결제 완료 이벤트는 재시도 대기(60초) 없이 바로 order.events-dlt로 간다")
    void nullAddress_dltWithoutRetry() {
        assertDltWithoutRetry(withAddress(paidEvent(orderId, orderId * 10), null));
    }

    @Test
    @DisplayName("수량 0 항목 이벤트는 재시도 없이 DLT로 가고, DLT 예외 헤더에 배송지 값이 없다")
    void quantityZero_dltWithoutRetry_noAddressInExceptionHeaders() {
        ConsumerRecord<String, String> dead = assertDltWithoutRetry(
                withItems(paidEvent(orderId, orderId * 10), List.of(item(orderId * 10, 1L, 0))));

        StringBuilder exceptionHeaders = new StringBuilder();
        for (Header header : dead.headers()) {
            if (header.key().contains("exception")) {
                exceptionHeaders.append(new String(header.value(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        assertThat(exceptionHeaders.toString())
                .contains("items.quantity")
                .doesNotContain("홍길동", "010-1234-5678", "06236", "테헤란로", "101동", "문 앞에");
    }

    private ConsumerRecord<String, String> assertDltWithoutRetry(OrderPaidEvent event) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "dlt-noretry-" + UUID.randomUUID(), true);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "order.events-dlt");
            UUID eventId = send(event);

            ConsumerRecord<String, String> dead = null;
            long deadline = System.currentTimeMillis() + NO_RETRY_LIMIT.toMillis();
            while (dead == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1))) {
                    if (String.valueOf(orderId).equals(r.key())) dead = r;
                }
            }
            assertThat(dead).as("재시도 없이 %s초 안에 order.events-dlt에 도착해야 한다", NO_RETRY_LIMIT.toSeconds()).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from processed_event where event_id = ? and consumer = 'delivery'", Integer.class, eventId))
                    .isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from delivery where order_id = ?", Integer.class, orderId))
                    .isZero();
            return dead;
        }
    }

    private UUID send(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        OutboxEvent outbox = OutboxEvent.create(envelope, objectMapper.writeValueAsString(envelope.payload()));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "order.events", String.valueOf(orderId), eventMessages.toJson(outbox));
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
        eventIds.add(envelope.eventId());
        return envelope.eventId();
    }
}
