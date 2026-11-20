package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.event.OutboxTestEvent;

/**
 * 발행(EventPublisher) → outbox → 릴레이 → Kafka → 컨슈머(중복 처리)까지 실제로 흘려 본다.
 * Kafka는 EmbeddedKafka(테스트 JVM 안의 브로커)를 쓰고, DB는 로컬 Docker PostgreSQL을 쓴다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",   // 릴레이는 직접 호출한다
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
// 컨슈머가 구독하기 전에 토픽을 운영과 같은 파티션 수(3)로 만들어 둔다. 없으면 브로커가 1개짜리로 자동 생성한 뒤
// 릴레이가 3개로 늘려, 컨슈머가 늘어난 파티션을 늦게(최대 5분) 알아채 메시지를 못 받는다
@EmbeddedKafka(topics = "test.events", partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class OutboxKafkaIntegrationTest {

    static final String CONSUMER = "outbox-it";

    /** 테스트 이벤트를 받는 컨슈머. 운영 컨슈머와 같은 방식(같은 트랜잭션 안에서 처리 기록 → 처리)으로 만든다 */
    static class TestEventConsumer {
        private final List<UUID> handled = new CopyOnWriteArrayList<>();

        @Autowired EventMessages eventMessages;
        @Autowired ProcessedEvents processedEvents;

        @KafkaListener(topics = "test.events", groupId = CONSUMER)
        @Transactional
        public void handle(String message) {
            EventEnvelope<DomainEvent> envelope = eventMessages.read(message);
            if (!(envelope.payload() instanceof OutboxTestEvent)) return;
            if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;   // 이미 처리한 이벤트
            handled.add(envelope.eventId());
        }

        // @Transactional 때문에 프록시가 주입되므로 필드가 아니라 메서드로 꺼낸다
        public List<UUID> handled() {
            return handled;
        }
    }

    @TestConfiguration
    static class ConsumerConfig {
        @Bean
        TestEventConsumer testEventConsumer() {
            return new TestEventConsumer();
        }
    }

    @Autowired private EventPublisher eventPublisher;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private EventMessages eventMessages;
    @Autowired private TestEventConsumer testEventConsumer;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    // 다른 테스트와 겹치지 않는 대상 ID
    private final Long testId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    private UUID eventId;

    @AfterEach
    void cleanUp() {
        if (eventId != null) outboxEventRepository.findByEventId(eventId).ifPresent(outboxEventRepository::delete);
        // 컨슈머가 처리한 이벤트(markerFor로 만든 것 포함)의 처리 기록을 지운다
        testEventConsumer.handled().forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
        testEventConsumer.handled().clear();
    }

    @Test
    @DisplayName("커밋된 이벤트를 릴레이가 <대상>.events 토픽에 키 = aggregateId, 값 = 봉투로 보내고 PUBLISHED로 바꾼다")
    void relayToKafka() {
        eventId = publish();

        relayUntilPublished();

        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps(embeddedKafka, "outbox-it-reader-" + testId, false),
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of("test.events"));   // 토픽은 릴레이가 처음 보낼 때 만든다
            ConsumerRecord<String, String> record = findRecord(consumer);

            assertThat(record.key()).isEqualTo(testId.toString());
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("OutboxTestEvent");

            EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
            assertThat(envelope.eventId()).isEqualTo(eventId);
            assertThat(envelope.aggregateType()).isEqualTo("TEST");
            assertThat(envelope.aggregateId()).isEqualTo(testId);
            assertThat(envelope.payload()).isEqualTo(new OutboxTestEvent(testId));
        }
    }

    @Test
    @DisplayName("같은 메시지가 두 번 와도(재발행·재전달) 컨슈머는 eventId로 걸러 한 번만 처리한다")
    void consumerHandlesOnce() {
        eventId = publish();
        relayUntilPublished();
        await().atMost(Duration.ofSeconds(20)).until(() -> testEventConsumer.handled().contains(eventId));

        // 릴레이가 PUBLISHED를 커밋하기 전에 죽어 같은 이벤트를 다시 보낸 상황
        OutboxEvent outbox = outboxEventRepository.findByEventId(eventId).orElseThrow();
        kafkaTemplate.send("test.events", testId.toString(), eventMessages.toJson(outbox)).join();
        kafkaTemplate.send("test.events", "marker-" + testId, eventMessages.toJson(markerFor(outbox))).join();

        // 뒤에 보낸 다른 이벤트까지 처리될 때까지 기다리면, 앞의 중복 메시지도 이미 받은 것이다(한 파티션이라 순서대로 받음)
        await().atMost(Duration.ofSeconds(20)).until(() -> testEventConsumer.handled().size() >= 2
                && testEventConsumer.handled().stream().anyMatch(id -> !id.equals(eventId)));

        assertThat(testEventConsumer.handled().stream().filter(eventId::equals)).hasSize(1);
        Integer recorded = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = ?", Integer.class, eventId, CONSUMER);
        assertThat(recorded).isEqualTo(1);
    }

    private UUID publish() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publish(new OutboxTestEvent(testId)));
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("TEST") && outbox.getAggregateId().equals(testId))
                .findFirst().orElseThrow()
                .getEventId();
    }

    // 로컬 DB에 남은 다른 PENDING 이벤트가 많으면 한 번에 닿지 않을 수 있어 여러 번 돌린다
    private void relayUntilPublished() {
        for (int i = 0; i < 100; i++) {
            outboxRelay.relayPending();
            if (outboxEventRepository.findByEventId(eventId).orElseThrow().getStatus() == OutboxEventStatus.PUBLISHED) return;
        }
        throw new AssertionError("릴레이를 100번 돌려도 PUBLISHED가 되지 않습니다: eventId=" + eventId);
    }

    private ConsumerRecord<String, String> findRecord(Consumer<String, String> consumer) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                if (record.value().contains(eventId.toString())) return record;
            }
        }
        throw new AssertionError("test.events에서 eventId=" + eventId + " 메시지를 찾지 못했습니다");
    }

    // 중복 메시지 뒤에 보내는 다른 이벤트(새 eventId). 이 메시지가 처리되면 앞의 중복 메시지도 받은 것이다
    private OutboxEvent markerFor(OutboxEvent original) {
        EventEnvelope<OutboxTestEvent> marker = EventEnvelope.wrap(new OutboxTestEvent(original.getAggregateId()));
        return OutboxEvent.create(marker, original.getPayload());
    }
}
