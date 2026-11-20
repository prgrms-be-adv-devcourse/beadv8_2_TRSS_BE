package com.backend.boundedContext.payment.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.app.PaymentCreateWalletUseCase;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
import com.backend.global.outbox.OutboxRelay;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.MemberSignedUpEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * 가입 이벤트가 outbox → 릴레이 → Kafka(member.events) → 결제 컨슈머를 거쳐 지갑이 되는지 확인한다(TRSS-133 5-2).
 * Kafka는 EmbeddedKafka(테스트 JVM 안의 브로커), DB는 로컬 Docker PostgreSQL을 쓴다. 릴레이는 직접 호출한다.
 *
 * 이 테스트는 outbox를 혼자 읽는다고 가정한다 (SettlementOutboxRelayIntegrationTest와 같은 주의사항).
 * - 같은 DB를 쓰는 앱(local 프로필)이 떠 있으면, 그 앱의 릴레이가 테스트 이벤트를 자기 Kafka로 먼저 보내 실패한다.
 * - DB에 남아 있던 다른 PENDING 이벤트도 함께 EmbeddedKafka로 발행된다. 그래서 PUBLISHED가 될 때까지 릴레이를 여러 번 돌린다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",   // 릴레이는 직접 호출한다
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
// 컨슈머가 구독하기 전에 토픽을 운영과 같은 파티션 수(3)로 만들어 둔다
@EmbeddedKafka(topics = "member.events", partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class PaymentEventListenerKafkaIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private EventPublisher eventPublisher;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private EventMessages eventMessages;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 컨슈머가 지갑 생성을 몇 번 불렀는지 확인하고, 첫 호출만 실패시키는 데 쓴다. 기본은 실제 메서드를 부른다 */
    @MockitoSpyBean
    private PaymentCreateWalletUseCase paymentCreateWalletUseCase;

    // 다른 테스트·시드와 겹치지 않는 회원 ID. marker는 중복 메시지 뒤에 보내는 다른 회원
    private final long memberId = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);
    private final long markerMemberId = memberId + 100;

    /** 테스트가 만든 이벤트 ID(처리 기록 정리용) */
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        walletRepository.findByMemberId(memberId).ifPresent(walletRepository::delete);
        walletRepository.findByMemberId(markerMemberId).ifPresent(walletRepository::delete);
        eventIds.forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
        outboxEventRepository.deleteAll(findOutbox(memberId));
    }

    @Test
    @DisplayName("가입 이벤트는 outbox에 PENDING으로 저장되고, 릴레이가 Kafka로 보내면 결제 컨슈머가 잔액 0인 지갑을 만든다")
    void kafkaConsumerCreatesWallet() {
        UUID eventId = publish();

        // 바로 처리하지 않으므로 릴레이 전에는 지갑이 없다
        assertThat(outboxEventRepository.findByEventId(eventId).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PENDING);
        assertThat(walletRepository.findByMemberId(memberId)).isEmpty();

        relayUntilPublished(eventId);

        await().atMost(WAIT).until(() -> walletRepository.findByMemberId(memberId).isPresent());
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isZero();
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 메시지가 다시 와도(릴레이 재발행·Kafka 재전달) eventId로 걸러 지갑 생성은 한 번만 부른다")
    void duplicateMessageIsHandledOnce() {
        UUID eventId = publish();
        relayUntilPublished(eventId);
        await().atMost(WAIT).until(() -> walletRepository.findByMemberId(memberId).isPresent());

        // 릴레이가 PUBLISHED를 커밋하기 전에 죽어 같은 이벤트를 다시 보낸 상황
        OutboxEvent outbox = outboxEventRepository.findByEventId(eventId).orElseThrow();
        kafkaTemplate.send("member.events", String.valueOf(memberId), eventMessages.toJson(outbox)).join();
        // 같은 키(같은 파티션)로 다른 회원 이벤트를 뒤에 보낸다. 이게 처리되면 앞의 중복 메시지도 이미 받은 것이다
        kafkaTemplate.send("member.events", String.valueOf(memberId), eventMessages.toJson(markerOutbox())).join();
        await().atMost(WAIT).until(() -> walletRepository.findByMemberId(markerMemberId).isPresent());

        // 지갑 존재 확인으로 건너뛴 것이 아니라, 처리 기록으로 걸러 지갑 생성 자체를 다시 부르지 않았다
        verify(paymentCreateWalletUseCase, times(1)).createWallet(eq(memberId));
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("지갑 생성이 실패하면 처리 기록도 함께 롤백되고, Kafka가 다시 전달해 지갑이 생긴다 (가입은 이미 끝나 영향 없음)")
    void failedProcessingIsRedelivered() {
        willThrow(new IllegalStateException("지갑 생성 일시 실패"))
                .willCallRealMethod()
                .given(paymentCreateWalletUseCase).createWallet(eq(memberId));   // 이 테스트의 회원만 첫 호출 실패
        UUID eventId = publish();

        relayUntilPublished(eventId);

        // 첫 처리가 실패해도 기록이 롤백되므로 재전달 때 다시 처리한다(기록이 남았다면 건너뛰어 지갑이 영영 생기지 않는다)
        await().atMost(WAIT).until(() -> walletRepository.findByMemberId(memberId).isPresent());
        verify(paymentCreateWalletUseCase, times(2)).createWallet(eq(memberId));
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    private UUID publish() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publish(new MemberSignedUpEvent(memberId)));
        UUID eventId = findOutbox(memberId).getFirst().getEventId();
        eventIds.add(eventId);
        return eventId;
    }

    /** DB에 저장하지 않고 메시지만 만드는 다른 회원의 가입 이벤트 */
    private OutboxEvent markerOutbox() {
        MemberSignedUpEvent marker = new MemberSignedUpEvent(markerMemberId);
        EventEnvelope<MemberSignedUpEvent> envelope = EventEnvelope.wrap(marker);
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

    private int processedCount(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'payment'", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private List<OutboxEvent> findOutbox(long id) {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("MEMBER") && outbox.getAggregateId().equals(id))
                .toList();
    }
}
