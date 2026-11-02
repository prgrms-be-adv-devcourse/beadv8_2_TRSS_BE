package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backend.shared.event.EventEnvelope;
import com.backend.shared.event.OutboxTestEvent;

/**
 * Outbox 릴레이가 미발행 이벤트를 보내고 상태를 바꾸는지 실제 DB로 확인한다. Kafka 발행(OutboxKafkaSender)은 mock으로 바꾼다.
 * 실제 Kafka 발행은 OutboxKafkaIntegrationTest에서 확인한다.
 * 스케줄러는 끄고 릴레이를 직접 호출한다. 로컬 DB에 남아 있던 다른 PENDING 이벤트도 함께 처리될 수 있다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class OutboxRelayTest {

    @MockitoBean
    private OutboxKafkaSender outboxKafkaSender;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPolicy outboxPolicy;

    @Autowired
    private ApplicationContext applicationContext;

    // 다른 테스트와 겹치지 않는 대상 ID
    private final Long testId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        eventIds.forEach(eventId -> outboxEventRepository.findByEventId(eventId).ifPresent(outboxEventRepository::delete));
    }

    @Test
    @DisplayName("테스트에서는 릴레이 스케줄러가 등록되지 않는다(다른 테스트의 PENDING 이벤트를 몰래 발행하지 않게)")
    void relaySchedulerDisabledInTests() {
        assertThat(applicationContext.getBeanNamesForType(OutboxRelayScheduler.class)).isEmpty();
    }

    @Test
    @DisplayName("Kafka가 받으면(ack) 이벤트는 PUBLISHED가 된다")
    void relay() {
        UUID eventId = savePending();

        OutboxEvent relayed = relayUntil(eventId, outbox -> outbox.getStatus() != OutboxEventStatus.PENDING);

        assertThat(relayed.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(relayed.getPublishedAt()).isNotNull();
        assertThat(relayed.getRetryCount()).isZero();
        then(outboxKafkaSender).should().send(argThat(outbox -> outbox.getEventId().equals(eventId)));
    }

    @Test
    @DisplayName("Kafka가 메시지를 거부하면 PENDING으로 남고 재시도 횟수가 오른다")
    void rejectedCountsRetry() {
        UUID eventId = savePending();
        doThrow(new IllegalStateException("거부")).when(outboxKafkaSender).send(argThat(outbox -> outbox.getEventId().equals(eventId)));

        OutboxEvent failed = relayUntil(eventId, outbox -> outbox.getRetryCount() > 0);

        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failed.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("재시도 한도만큼 거부되면 FAILED가 되고 더 이상 보내지 않는다")
    void failedAfterMaxRetry() {
        UUID eventId = savePending();
        doThrow(new IllegalStateException("거부")).when(outboxKafkaSender).send(argThat(outbox -> outbox.getEventId().equals(eventId)));

        OutboxEvent failed = relayUntil(eventId, outbox -> outbox.getStatus() == OutboxEventStatus.FAILED);
        assertThat(failed.getRetryCount()).isEqualTo(outboxPolicy.getMaxRetryCount());

        outboxRelay.relayPending();
        assertThat(outboxEventRepository.findByEventId(eventId).orElseThrow().getRetryCount())
                .isEqualTo(outboxPolicy.getMaxRetryCount());
    }

    @Test
    @DisplayName("Kafka에 닿지 못하면 재시도 횟수를 쓰지 않고 이번 실행을 바로 멈춘다(브로커가 살아나면 다시 보냄)")
    void kafkaUnavailableStopsRun() {
        UUID eventId = savePending();
        doThrow(new KafkaUnavailableException(new RuntimeException("브로커 없음"))).when(outboxKafkaSender).send(any());

        assertThat(outboxRelay.relayPending()).isZero();

        then(outboxKafkaSender).should(times(1)).send(any());   // 첫 이벤트에서 멈추고 다음 이벤트는 보내지 않음
        OutboxEvent outbox = outboxEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(outbox.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("같은 대상의 앞 이벤트가 실패해 PENDING이면 뒤 이벤트는 보내지 않고, 앞 이벤트가 끝나면(FAILED) 보낸다")
    void keepOrderPerAggregate() {
        UUID earlier = savePending();
        UUID later = savePending();
        doThrow(new IllegalStateException("거부")).when(outboxKafkaSender).send(argThat(outbox -> outbox.getEventId().equals(earlier)));

        relayUntil(earlier, outbox -> outbox.getRetryCount() > 0);   // 앞 이벤트가 한 번 실패

        assertThat(outboxEventRepository.findByEventId(later).orElseThrow().getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        then(outboxKafkaSender).should(never()).send(argThat(outbox -> outbox.getEventId().equals(later)));

        relayUntil(earlier, outbox -> outbox.getStatus() == OutboxEventStatus.FAILED);   // 앞 이벤트가 한도까지 실패
        relayUntil(later, outbox -> outbox.getStatus() == OutboxEventStatus.PUBLISHED);

        then(outboxKafkaSender).should(atLeastOnce()).send(argThat(outbox -> outbox.getEventId().equals(later)));
    }

    // 릴레이를 기다리는 테스트 이벤트를 outbox에 넣는다(같은 testId라 같은 대상)
    private UUID savePending() {
        EventEnvelope<OutboxTestEvent> envelope = EventEnvelope.wrap(new OutboxTestEvent(testId));
        outboxEventRepository.save(OutboxEvent.create(envelope, "{\"testId\":" + testId + "}"));
        eventIds.add(envelope.eventId());
        return envelope.eventId();
    }

    // 로컬 DB에 남은 다른 PENDING 이벤트가 많으면 한 번에 닿지 않을 수 있어, 조건을 만족할 때까지 릴레이를 다시 돌린다
    private OutboxEvent relayUntil(UUID eventId, Predicate<OutboxEvent> condition) {
        for (int i = 0; i < 100; i++) {
            outboxRelay.relayPending();
            OutboxEvent outbox = outboxEventRepository.findByEventId(eventId).orElseThrow();
            if (condition.test(outbox)) return outbox;
        }
        throw new AssertionError("릴레이를 100번 돌려도 조건을 만족하지 않습니다: eventId=" + eventId);
    }
}
