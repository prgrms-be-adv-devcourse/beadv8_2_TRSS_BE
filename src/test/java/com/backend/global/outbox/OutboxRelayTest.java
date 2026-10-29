package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.MemberSignedUpEvent;

/**
 * Outbox 릴레이가 미발행 이벤트를 리스너에 전달하고 상태를 바꾸는지 실제 DB로 확인한다.
 * 스케줄러는 끄고 릴레이를 직접 호출한다. 로컬 DB에 남아 있던 다른 PENDING 이벤트도 함께 발행될 수 있다.
 * 가입 이벤트는 지금 EventPublisher가 같은 트랜잭션에서 바로 전달하므로, 여기서는 PENDING 행을 직접 넣어 릴레이를 확인한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class OutboxRelayTest {

    /** com.backend.shared 밖에 있어 릴레이가 모르는 이벤트 */
    record UnknownEvent(Long unknownId) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "TEST";
        }

        @Override
        public Long aggregateId() {
            return unknownId;
        }
    }

    /** 가입 이벤트와 같은 대상(MEMBER)이지만 릴레이가 모르는 이벤트. 앞 이벤트를 실패시키는 데 쓴다 */
    record UnknownMemberEvent(Long memberId) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "MEMBER";
        }

        @Override
        public Long aggregateId() {
            return memberId;
        }
    }

    @TestConfiguration
    static class FailingListenerConfig {
        @Bean
        FailingSignUpListener failingSignUpListener() {
            return new FailingSignUpListener();
        }
    }

    /** 지정한 회원의 가입 이벤트에서 실패한다. 지갑 생성 리스너보다 뒤에 실행된다 */
    static class FailingSignUpListener {
        volatile Long failMemberId;

        @EventListener
        @Order(Ordered.LOWEST_PRECEDENCE)
        void handle(MemberSignedUpEvent event) {
            if (event.memberId().equals(failMemberId)) {
                throw new IllegalStateException("테스트용 리스너 실패");
            }
        }
    }

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPolicy outboxPolicy;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private FailingSignUpListener failingSignUpListener;

    // 다른 테스트·시드 회원과 겹치지 않는 회원 ID
    private final Long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    private UUID eventId;

    private UUID laterEventId;

    @AfterEach
    void cleanUp() {
        failingSignUpListener.failMemberId = null;
        if (eventId != null) outboxEventRepository.findByEventId(eventId).ifPresent(outboxEventRepository::delete);
        if (laterEventId != null) outboxEventRepository.findByEventId(laterEventId).ifPresent(outboxEventRepository::delete);
        walletRepository.findByMemberId(memberId).ifPresent(walletRepository::delete);
    }

    @Test
    @DisplayName("가입 이벤트를 릴레이하면 지갑이 만들어지고 이벤트는 PUBLISHED가 된다")
    void relay() {
        eventId = publish(new MemberSignedUpEvent(memberId));
        assertThat(walletRepository.findByMemberId(memberId)).isEmpty();

        OutboxEvent relayed = relayUntil(outbox -> outbox.getStatus() != OutboxEventStatus.PENDING);

        assertThat(relayed.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(relayed.getPublishedAt()).isNotNull();
        assertThat(relayed.getRetryCount()).isZero();
        assertThat(walletRepository.findByMemberId(memberId)).isPresent();
    }

    @Test
    @DisplayName("리스너 하나라도 실패하면 다른 리스너의 변경도 롤백되고, 이벤트는 PENDING으로 남아 재시도 횟수가 오른다")
    void listenerFailureRollsBack() {
        failingSignUpListener.failMemberId = memberId;
        eventId = publish(new MemberSignedUpEvent(memberId));

        OutboxEvent failed = relayUntil(outbox -> outbox.getRetryCount() > 0);

        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(walletRepository.findByMemberId(memberId)).isEmpty();   // 지갑 생성 리스너의 INSERT도 롤백
    }

    @Test
    @DisplayName("재시도 한도만큼 실패하면 FAILED가 되고 더 이상 발행하지 않는다")
    void failedAfterMaxRetry() {
        EventEnvelope<UnknownEvent> envelope = EventEnvelope.wrap(new UnknownEvent(memberId));
        eventId = envelope.eventId();
        outboxEventRepository.save(OutboxEvent.create(envelope, "{\"unknownId\":" + memberId + "}"));

        OutboxEvent failed = relayUntil(outbox -> outbox.getStatus() == OutboxEventStatus.FAILED);
        assertThat(failed.getRetryCount()).isEqualTo(outboxPolicy.getMaxRetryCount());

        outboxRelay.relayPending();
        assertThat(outboxEventRepository.findByEventId(eventId).orElseThrow().getRetryCount())
                .isEqualTo(outboxPolicy.getMaxRetryCount());
    }

    @Test
    @DisplayName("같은 대상의 앞 이벤트가 실패해 PENDING이면 뒤 이벤트는 발행하지 않고, 앞 이벤트가 끝나면(FAILED) 발행한다")
    void keepOrderPerAggregate() {
        EventEnvelope<UnknownMemberEvent> earlier = EventEnvelope.wrap(new UnknownMemberEvent(memberId));
        eventId = earlier.eventId();
        outboxEventRepository.save(OutboxEvent.create(earlier, "{\"memberId\":" + memberId + "}"));
        laterEventId = publish(new MemberSignedUpEvent(memberId));

        relayUntil(outbox -> outbox.getRetryCount() > 0);   // 앞 이벤트가 한 번 실패

        assertThat(outboxEventRepository.findByEventId(laterEventId).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PENDING);
        assertThat(walletRepository.findByMemberId(memberId)).isEmpty();

        relayUntil(outbox -> outbox.getStatus() == OutboxEventStatus.FAILED);   // 앞 이벤트가 한도까지 실패
        outboxRelay.relayPending();

        assertThat(outboxEventRepository.findByEventId(laterEventId).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(walletRepository.findByMemberId(memberId)).isPresent();
    }

    // 릴레이를 기다리는 가입 이벤트를 outbox에 넣는다
    private UUID publish(MemberSignedUpEvent event) {
        EventEnvelope<MemberSignedUpEvent> envelope = EventEnvelope.wrap(event);
        outboxEventRepository.save(OutboxEvent.create(envelope, "{\"memberId\":" + event.memberId() + "}"));
        return envelope.eventId();
    }

    // 로컬 DB에 남은 다른 PENDING 이벤트가 많으면 한 번에 닿지 않을 수 있어, 조건을 만족할 때까지 릴레이를 다시 돌린다
    private OutboxEvent relayUntil(Predicate<OutboxEvent> condition) {
        for (int i = 0; i < 100; i++) {
            outboxRelay.relayPending();
            OutboxEvent outbox = outboxEventRepository.findByEventId(eventId).orElseThrow();
            if (condition.test(outbox)) return outbox;
        }
        throw new AssertionError("릴레이를 100번 돌려도 조건을 만족하지 않습니다: eventId=" + eventId);
    }
}
