package com.backend.boundedContext.settlement.in.eventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
import com.backend.global.outbox.OutboxRelay;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.member.event.SellerSanctionedEvent;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;

/**
 * 판매자 제재 이벤트가 outbox → 릴레이 → Kafka(seller.events) → 정산 컨슈머를 거쳐 지급 전 정산서를 보류하는지 확인한다.
 * 실제로는 회원 서비스가 발행하지만, 같은 봉투·토픽 규칙(aggregateType SELLER → seller.events, 키 sellerId)을 쓰므로
 * 이 앱의 outbox로 발행해 같은 경로를 재현한다.
 * Kafka는 EmbeddedKafka, DB는 로컬 Docker PostgreSQL(settlement 스키마)을 쓴다. 릴레이는 직접 호출한다.
 * SettlementOutboxRelayIntegrationTest와 같은 이유로, 같은 DB를 쓰는 로컬 앱(local 프로필)은 꺼 두고 돌린다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",   // 릴레이는 직접 호출한다
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(topics = {"seller.events", "seller.events-dlt"}, partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SettlementSellerEventKafkaIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private EventPublisher eventPublisher;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EventMessages eventMessages;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    // 다른 테스트·시드와 겹치지 않는 판매자 ID
    private final long sellerId = ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);

    private Settlement settlement;

    /** settlement 외에 이 테스트가 만든 정산서 ID (정리용) */
    private final List<Long> extraSettlementIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (settlement != null) extraSettlementIds.add(settlement.getId());
        // 정산서·처리 이력은 @PreRemove로 JPA 삭제를 막으므로(원장 무결성 1단계) 테스트 정리는 SQL로 한다
        for (Long id : extraSettlementIds) {
            jdbcTemplate.update("delete from settlement_process_history where target_type = ? and target_id = ?",
                    SettlementHistoryTargetType.SETTLEMENT.name(), id);
            jdbcTemplate.update("delete from settlement where id = ?", id);
        }
        for (OutboxEvent outbox : findOutbox()) {
            jdbcTemplate.update("delete from processed_event where event_id = ?", outbox.getEventId());
            outboxEventRepository.delete(outbox);
        }
    }

    @Test
    @DisplayName("제재 이벤트가 Kafka로 오면 그 판매자의 READY 정산서를 HOLD로 바꾸고 보류 이력을 EVENT로 남긴다")
    void sanctionedEvent_holdsReadySettlement() {
        settlement = saveReadySettlement();

        UUID eventId = publish(new SellerSanctionedEvent(sellerId, "위조 상품 판매"));
        relayUntilPublished(eventId);

        await().atMost(WAIT).until(() -> status() == SettlementStatus.HOLD);
        assertThat(settlementRepository.findById(settlement.getId()).orElseThrow().getHoldReason())
                .isEqualTo("위조 상품 판매");
        List<SettlementProcessHistory> histories = histories();
        assertThat(histories).hasSize(1);
        assertThat(histories.getFirst().getStage()).isEqualTo(SettlementHistoryStage.HOLD);
        assertThat(histories.getFirst().getTriggerType()).isEqualTo(SettlementTrigger.EVENT);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("정산이 할 일이 없는 판매자 이벤트(철회 요청)는 무시하고 처리 기록도 남기지 않는다")
    void otherSellerEvent_ignored() {
        settlement = saveReadySettlement();

        UUID ignoredId = publish(new SellerWithdrawalRequestedEvent(sellerId, "폐업 예정"));
        relayUntilPublished(ignoredId);
        // 무시한 메시지 뒤에 제재 이벤트를 보내, 그것이 처리됐을 때 앞 메시지도 이미 지나갔음을 확인한다(같은 키 = 같은 파티션 순서)
        UUID sanctionedId = publish(new SellerSanctionedEvent(sellerId, "위조 상품 판매"));
        relayUntilPublished(sanctionedId);

        await().atMost(WAIT).until(() -> processedCount(sanctionedId) == 1);
        assertThat(processedCount(ignoredId)).isZero();
        assertThat(histories()).hasSize(1);   // 제재로 인한 보류 1건뿐
    }

    @Test
    @DisplayName("제재 이벤트는 READY·FAILED·MANUAL_REQUIRED 정산서를 모두 HOLD로 바꾸고 PAID는 그대로 둔다. 같은 메시지가 다시 와도 이력이 늘지 않는다")
    void sanctionedEvent_holdsPayableSettlements_andIgnoresRedelivery() {
        Long ready = saveSettlement("2026-07", s -> { });
        Long failed = saveSettlement("2026-08", s -> s.markFailed("일시 오류", LocalDateTime.now(), 3, 10));
        Long manual = saveSettlement("2026-09", s -> s.markManualRequired("금액 불일치"));
        Long paid = saveSettlement("2026-06", s -> s.markPaid(1L, LocalDateTime.now()));

        UUID eventId = publish(new SellerSanctionedEvent(sellerId, "위조 상품 판매"));
        relayUntilPublished(eventId);
        await().atMost(WAIT).until(() -> processedCount(eventId) == 1);

        assertThat(statusOf(ready)).isEqualTo(SettlementStatus.HOLD);
        assertThat(statusOf(failed)).isEqualTo(SettlementStatus.HOLD);
        assertThat(statusOf(manual)).isEqualTo(SettlementStatus.HOLD);
        assertThat(statusOf(paid)).isEqualTo(SettlementStatus.PAID);
        assertThat(historyCount()).isEqualTo(3);

        // 같은 메시지를 다시 보낸다(Kafka 재전달 흉내). 뒤에 다른 제재 이벤트를 보내, 그것이 처리됐을 때
        // 앞의 재전달 메시지도 이미 지나갔음을 확인한다(같은 키 = 같은 파티션 순서)
        OutboxEvent first = outboxEventRepository.findByEventId(eventId).orElseThrow();
        kafkaTemplate.send(sellerEventRecord(String.valueOf(sellerId), eventMessages.toJson(first))).join();
        UUID nextId = publish(new SellerSanctionedEvent(sellerId, "재전달 확인용"));
        relayUntilPublished(nextId);
        await().atMost(WAIT).until(() -> processedCount(nextId) == 1);

        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(3);   // 이미 HOLD라 다음 제재 이벤트도 보류를 더하지 않는다
    }

    @Test
    @DisplayName("처리할 수 없는 메시지(JSON 파싱 실패)는 재시도 없이 seller.events-dlt로 보낸다")
    void brokenMessage_goesToDlt() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "dlt-check-" + UUID.randomUUID(), true);
        props.put("auto.offset.reset", "earliest");
        try (org.apache.kafka.clients.consumer.Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "seller.events-dlt");
            String key = "broken-" + sellerId;
            kafkaTemplate.send(sellerEventRecord(key, "{not json")).join();

            ConsumerRecord<String, String> dead = null;
            long deadline = System.currentTimeMillis() + WAIT.toMillis();
            while (dead == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                    if (key.equals(r.key())) dead = r;
                }
            }
            assertThat(dead).as("seller.events-dlt에 도착해야 한다").isNotNull();
            assertThat(dead.value()).isEqualTo("{not json");
        }
    }

    /** eventType 헤더를 붙인 seller.events 메시지 (릴레이가 보내는 형식과 같게) */
    private static ProducerRecord<String, String> sellerEventRecord(String key, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>("seller.events", key, value);
        record.headers().add("eventType", SellerSanctionedEvent.class.getSimpleName().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private Long saveSettlement(String month, Consumer<Settlement> change) {
        Long id = new TransactionTemplate(transactionManager).execute(status -> {
            Settlement s = settlementRepository.save(Settlement.create(sellerId, month, null));
            s.assignIdempotencyKey();
            change.accept(s);
            return s.getId();
        });
        extraSettlementIds.add(id);
        return id;
    }

    private SettlementStatus statusOf(Long settlementId) {
        return settlementRepository.findById(settlementId).orElseThrow().getStatus();
    }

    /** 이 테스트가 만든 정산서들의 처리 이력 수 */
    private int historyCount() {
        return extraSettlementIds.stream()
                .mapToInt(id -> settlementProcessHistoryRepository
                        .findByTargetTypeAndTargetIdOrderByIdAsc(SettlementHistoryTargetType.SETTLEMENT, id).size())
                .sum();
    }

    private Settlement saveReadySettlement() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Settlement s = settlementRepository.save(Settlement.create(sellerId, "2026-10", null));
            s.assignIdempotencyKey();
            return s;
        });
    }

    private UUID publish(DomainEvent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publish(event));
        return findOutbox().getLast().getEventId();
    }

    /** 다른 PENDING 이벤트가 남아 있어도 테스트 이벤트가 PUBLISHED가 될 때까지 릴레이를 돌린다 */
    private void relayUntilPublished(UUID eventId) {
        for (int i = 0; i < 100; i++) {
            outboxRelay.relayPending();
            if (outboxEventRepository.findByEventId(eventId).orElseThrow().getStatus() == OutboxEventStatus.PUBLISHED) return;
        }
        throw new AssertionError("릴레이를 100번 돌려도 PUBLISHED가 되지 않습니다: eventId=" + eventId);
    }

    private SettlementStatus status() {
        return settlementRepository.findById(settlement.getId()).orElseThrow().getStatus();
    }

    private List<SettlementProcessHistory> histories() {
        return settlementProcessHistoryRepository.findByTargetTypeAndTargetIdOrderByIdAsc(
                SettlementHistoryTargetType.SETTLEMENT, settlement.getId());
    }

    private int processedCount(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'settlement'", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    /** 이 테스트가 만든 판매자 이벤트 outbox (id 오름차순) */
    private List<OutboxEvent> findOutbox() {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("SELLER") && outbox.getAggregateId().equals(sellerId))
                .sorted(Comparator.comparing(OutboxEvent::getId))
                .toList();
    }
}
