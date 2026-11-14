package com.backend.boundedContext.settlement.in;

import com.backend.boundedContext.settlement.app.SettlementCreateCandidatesUseCase;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
import com.backend.global.outbox.OutboxRelay;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.order.event.OrderConfirmedEvent;
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
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 구매 확정 이벤트가 outbox → 릴레이 → Kafka(order.events) → 정산 컨슈머를 거쳐 정산 후보가 되는지 확인한다(TRSS-133 5-1).
 * Kafka는 EmbeddedKafka(테스트 JVM 안의 브로커), DB는 로컬 Docker PostgreSQL을 쓴다. 릴레이는 직접 호출한다.
 *
 * 이 테스트는 outbox를 혼자 읽는다고 가정한다. 아래 경우에는 실패하거나 로컬 DB를 바꿀 수 있다.
 * - 같은 DB를 쓰는 앱(local 프로필)이 떠 있으면, 그 앱의 릴레이가 테스트 이벤트를 자기 Kafka로 먼저 보내 실패한다.
 *   테스트를 돌리기 전에 로컬 앱을 꺼 둔다.
 * - DB에 남아 있던 다른 PENDING 이벤트(시드 등)도 함께 EmbeddedKafka로 발행된다(cleanUp에서 지우지 않음).
 *   그래서 테스트 이벤트가 PUBLISHED가 될 때까지 릴레이를 여러 번 돌린다(relayUntilPublished).
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",   // 릴레이는 직접 호출한다
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
// 컨슈머가 구독하기 전에 토픽을 운영과 같은 파티션 수(3)로 만들어 둔다(OutboxKafkaIntegrationTest와 같은 이유)
@EmbeddedKafka(topics = "order.events", partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SettlementOutboxRelayIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    /** 재시도 대기(1+2+4+8초)를 다 쓰고 DLT로 가기까지 넉넉히 기다린다 */
    private static final Duration DLT_WAIT = Duration.ofSeconds(60);
    private static final String DLT_TOPIC = "order.events-dlt";

    @Autowired private EventPublisher eventPublisher;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private SettlementCandidateRepository settlementCandidateRepository;
    @Autowired private SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    /** 깨진 메시지를 몇 번 읽었는지(재시도했는지) 확인하는 데 쓴다. 기본은 실제 메서드를 부른다 */
    @MockitoSpyBean
    private EventMessages eventMessages;

    /** 컨슈머가 후보 생성을 몇 번 불렀는지 확인하고, 첫 호출만 실패시키는 데 쓴다. 기본은 실제 메서드를 부른다 */
    @MockitoSpyBean
    private SettlementCreateCandidatesUseCase settlementCreateCandidatesUseCase;

    // 다른 테스트·시드(-1, -2, -3)와 겹치지 않는 주문·주문 항목 ID. marker는 중복 메시지 뒤에 보내는 다른 주문
    private final long orderId = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);
    private final long markerOrderId = orderId + 100;
    private final long sellerId = 7_777L;

    /** 테스트가 만든 이벤트 ID(처리 기록 정리용) */
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 정산 후보·처리 이력은 @PreRemove로 JPA 삭제를 막으므로(원장 무결성 1단계) 테스트 정리는 SQL로 한다
        List<SettlementCandidate> candidates = findCandidates(orderId, markerOrderId);
        for (SettlementCandidate candidate : candidates) {
            jdbcTemplate.update("delete from settlement_process_history where target_type = ? and target_id = ?",
                    SettlementHistoryTargetType.CANDIDATE.name(), candidate.getId());
            jdbcTemplate.update("delete from settlement_candidate where id = ?", candidate.getId());
        }
        eventIds.forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
        outboxEventRepository.deleteAll(findOutbox(orderId));
    }

    @Test
    @DisplayName("구매 확정 이벤트는 outbox에 PENDING으로 저장되고, 릴레이가 Kafka로 보내면 정산 컨슈머가 후보를 만든다")
    void kafkaConsumerCreatesCandidates() {
        UUID eventId = publish();

        // 바로 처리하지 않으므로 릴레이 전에는 후보가 없다
        assertThat(outboxEventRepository.findByEventId(eventId).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PENDING);
        assertThat(findCandidates(orderId)).isEmpty();

        relayUntilPublished(eventId);

        await().atMost(WAIT).until(() -> findCandidates(orderId).size() == 2);
        assertThat(findCandidates(orderId)).extracting(SettlementCandidate::getOrderItemId)
                .containsExactlyInAnyOrder(orderId, orderId + 1);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 메시지가 다시 와도(릴레이 재발행·Kafka 재전달) eventId로 걸러 후보 생성은 한 번만 한다")
    void duplicateMessageIsHandledOnce() {
        UUID eventId = publish();
        relayUntilPublished(eventId);
        await().atMost(WAIT).until(() -> findCandidates(orderId).size() == 2);

        // 릴레이가 PUBLISHED를 커밋하기 전에 죽어 같은 이벤트를 다시 보낸 상황
        OutboxEvent outbox = outboxEventRepository.findByEventId(eventId).orElseThrow();
        kafkaTemplate.send("order.events", String.valueOf(orderId), eventMessages.toJson(outbox)).join();
        // 같은 키(같은 파티션)로 다른 주문 이벤트를 뒤에 보낸다. 이게 처리되면 앞의 중복 메시지도 이미 받은 것이다
        kafkaTemplate.send("order.events", String.valueOf(orderId), eventMessages.toJson(markerOutbox())).join();
        await().atMost(WAIT).until(() -> findCandidates(markerOrderId).size() == 2);

        // order_item_id 유니크로 건너뛴 것이 아니라, 처리 기록으로 걸러 후보 생성 자체를 다시 부르지 않았다
        verify(settlementCreateCandidatesUseCase, times(1)).createCandidates(eq(orderId), any(), anyList());
        assertThat(findCandidates(orderId)).hasSize(2);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("후보 생성이 실패하면 처리 기록도 함께 롤백되고, Kafka가 다시 전달해 후보가 생긴다")
    void failedProcessingIsRedelivered() {
        willThrow(new IllegalStateException("후보 생성 일시 실패"))
                .willCallRealMethod()
                .given(settlementCreateCandidatesUseCase).createCandidates(eq(orderId), any(), anyList());   // 이 테스트의 주문만 첫 호출 실패
        UUID eventId = publish();

        relayUntilPublished(eventId);

        // 첫 처리가 실패해도 기록이 롤백되므로 재전달 때 다시 처리한다(기록이 남았다면 건너뛰어 후보가 영영 생기지 않는다)
        await().atMost(WAIT).until(() -> findCandidates(orderId).size() == 2);
        verify(settlementCreateCandidatesUseCase, times(2)).createCandidates(eq(orderId), any(), anyList());
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("후보 생성이 계속 실패하면 1·2·4·8초 간격으로 총 5번 처리한 뒤 DLT로 보내고, 처리 기록은 남기지 않는다")
    void repeatedFailureGoesToDeadLetterTopic() {
        willThrow(new IllegalStateException("후보 생성 계속 실패"))
                .given(settlementCreateCandidatesUseCase).createCandidates(eq(orderId), any(), anyList());   // 이 테스트의 주문만 매번 실패
        UUID eventId = publish();

        relayUntilPublished(eventId);

        ConsumerRecord<String, String> dead = findDeadLetter(eventId.toString());
        assertThat(dead.key()).isEqualTo(String.valueOf(orderId));
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("후보 생성 계속 실패");
        verify(settlementCreateCandidatesUseCase, times(5)).createCandidates(eq(orderId), any(), anyList());
        assertThat(findCandidates(orderId)).isEmpty();
        assertThat(processedCount(eventId)).isZero();
    }

    @Test
    @DisplayName("깨진 메시지(JSON 파싱 실패)는 다시 해도 안 되므로 재시도 없이 바로 DLT로 보낸다")
    void malformedMessageGoesToDeadLetterTopicWithoutRetry() {
        String broken = "{broken-" + orderId;

        kafkaTemplate.send("order.events", String.valueOf(orderId), broken).join();

        ConsumerRecord<String, String> dead = findDeadLetter(broken);
        assertThat(dead.value()).isEqualTo(broken);
        verify(eventMessages, times(1)).read(broken);
        verify(settlementCreateCandidatesUseCase, never()).createCandidates(eq(orderId), any(), anyList());
    }

    @Test
    @DisplayName("eventType 헤더가 다른 주문 이벤트면 payload를 읽지 않고 넘어간다(이 앱이 모르는 새 이벤트여도 DLT로 보내지 않음)")
    void otherEventTypeIsSkippedByHeader() {
        String unknown = "{\"eventType\":\"OrderShippedEvent\",\"orderId\":" + orderId + "}";   // 이 앱이 모르는 이벤트(읽으면 예외)
        ProducerRecord<String, String> record = new ProducerRecord<>("order.events", String.valueOf(orderId), unknown);
        record.headers().add("eventType", "OrderShippedEvent".getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record).join();
        // 같은 키(같은 파티션)로 구매 확정 이벤트를 뒤에 보낸다. 이게 처리되면 앞의 메시지도 이미 받은 것이다
        kafkaTemplate.send("order.events", String.valueOf(orderId), eventMessages.toJson(markerOutbox())).join();
        await().atMost(WAIT).until(() -> findCandidates(markerOrderId).size() == 2);

        verify(eventMessages, never()).read(unknown);
        verify(settlementCreateCandidatesUseCase, never()).createCandidates(eq(orderId), any(), anyList());
    }

    @Test
    @DisplayName("JSON은 맞지만 봉투 필드가 빠진 메시지도 재시도 없이 바로 DLT로 보낸다")
    void envelopeWithoutFieldGoesToDeadLetterTopicWithoutRetry() {
        String noEventType = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"orderId\":" + orderId + "}";

        kafkaTemplate.send("order.events", String.valueOf(orderId), noEventType).join();

        ConsumerRecord<String, String> dead = findDeadLetter(noEventType);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("eventType");
        verify(eventMessages, times(1)).read(noEventType);
    }

    /** 실제 구매 확정처럼 쓰기 트랜잭션 안에서 발행하고, outbox에 저장된 eventId를 돌려준다 */
    private UUID publish() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publish(event(orderId)));
        UUID eventId = findOutbox(orderId).getFirst().getEventId();
        eventIds.add(eventId);
        return eventId;
    }

    private OrderConfirmedEvent event(long id) {
        return new OrderConfirmedEvent(id, 3L, LocalDateTime.now(), ConfirmType.MANUAL,
                List.of(new ConfirmedOrderItemDto(id, 1L, sellerId, 10_000L, 1, 10_000L),
                        new ConfirmedOrderItemDto(id + 1, 2L, sellerId, 5_000L, 2, 10_000L)));
    }

    /** DB에 저장하지 않고 메시지만 만드는 다른 주문의 구매 확정 이벤트 */
    private OutboxEvent markerOutbox() {
        OrderConfirmedEvent marker = event(markerOrderId);
        EventEnvelope<OrderConfirmedEvent> envelope = EventEnvelope.wrap(marker);
        eventIds.add(envelope.eventId());
        return OutboxEvent.create(envelope, objectMapper.writeValueAsString(marker));
    }

    // 로컬 DB에 남은 다른 PENDING 이벤트가 많으면 한 번에 닿지 않을 수 있어 여러 번 돌린다
    private void relayUntilPublished(UUID eventId) {
        for (int i = 0; i < 100; i++) {
            outboxRelay.relayPending();
            if (outboxEventRepository.findByEventId(eventId).orElseThrow().getStatus() == OutboxEventStatus.PUBLISHED) return;
        }
        throw new AssertionError("릴레이를 100번 돌려도 PUBLISHED가 되지 않습니다: eventId=" + eventId);
    }

    /** DLT에서 값에 text가 들어 있는 메시지를 찾는다 */
    private ConsumerRecord<String, String> findDeadLetter(String text) {
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps(embeddedKafka, "settlement-dlt-reader-" + orderId, false),
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(DLT_TOPIC));   // DLT는 처음 보낼 때 만들어진다
            long deadline = System.nanoTime() + DLT_WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                    if (record.value().contains(text)) return record;
                }
            }
        }
        throw new AssertionError(DLT_WAIT.toSeconds() + "초 안에 DLT로 오지 않았습니다: " + text);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private int processedCount(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'settlement'", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private List<OutboxEvent> findOutbox(long id) {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("ORDER") && outbox.getAggregateId().equals(id))
                .toList();
    }

    private List<SettlementCandidate> findCandidates(long... orderIds) {
        Set<Long> ids = new HashSet<>();
        for (long id : orderIds) ids.add(id);
        return settlementCandidateRepository.findAll().stream()
                .filter(candidate -> ids.contains(candidate.getOrderId()))
                .toList();
    }
}