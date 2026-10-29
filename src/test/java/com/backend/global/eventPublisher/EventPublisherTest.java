package com.backend.global.eventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.member.event.MemberSignedUpEvent;

/**
 * EventPublisher가 호출한 쪽 트랜잭션 안에서 outbox에 저장하는지 실제 DB로 확인한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class EventPublisherTest {

    /** 다른 리스너가 반응하지 않는 테스트 전용 이벤트 */
    record TestEvent(Long testId) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "TEST";
        }

        @Override
        public Long aggregateId() {
            return testId;
        }
    }

    @TestConfiguration
    static class ListenerConfig {
        @Bean
        TestEventListener testEventListener() {
            return new TestEventListener();
        }
    }

    static class TestEventListener {
        final List<TestEvent> received = new ArrayList<>();

        @EventListener
        void handle(TestEvent event) {
            received.add(event);
        }
    }

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TestEventListener testEventListener;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WalletRepository walletRepository;

    // 다른 테스트와 겹치지 않는 집합 ID
    private final Long testId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll(findOutbox());
        outboxEventRepository.deleteAll(outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("MEMBER") && outbox.getAggregateId().equals(testId))
                .toList());
        walletRepository.findByMemberId(testId).ifPresent(walletRepository::delete);
        testEventListener.received.clear();
    }

    @Test
    @DisplayName("트랜잭션 안에서 발행하면 outbox에 PENDING으로 저장되고, 리스너에는 바로 전달하지 않는다(릴레이가 전달)")
    void publishInTransaction() {
        TestEvent event = new TestEvent(testId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publish(event));

        List<OutboxEvent> saved = findOutbox();
        assertThat(saved).hasSize(1);
        OutboxEvent outbox = saved.getFirst();
        assertThat(outbox.getEventType()).isEqualTo("TestEvent");
        assertThat(outbox.getAggregateType()).isEqualTo("TEST");
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(outbox.getPayload()).contains("\"testId\"").contains(testId.toString());
        assertThat(testEventListener.received).isEmpty();
    }

    @Test
    @DisplayName("가입 이벤트는 같은 트랜잭션에서 바로 지갑을 만들고, outbox에는 PUBLISHED로 남아 릴레이가 다시 보내지 않는다")
    void publishSameTransactionEvent() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publish(new MemberSignedUpEvent(testId));
            assertThat(walletRepository.findByMemberId(testId)).isPresent();   // 커밋 전, 같은 트랜잭션 안에서 이미 생성
        });

        assertThat(outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("MEMBER") && outbox.getAggregateId().equals(testId)))
                .singleElement()
                .satisfies(outbox -> {
                    assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
                    assertThat(outbox.getPublishedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션이 롤백되면 outbox에도 남지 않는다")
    void rollbackTogether() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publish(new TestEvent(testId));
            status.setRollbackOnly();
        });

        assertThat(findOutbox()).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 발행하면 예외가 나고 아무것도 저장·발행되지 않는다")
    void publishWithoutTransaction() {
        assertThatThrownBy(() -> eventPublisher.publish(new TestEvent(testId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(findOutbox()).isEmpty();
        assertThat(testEventListener.received).isEmpty();
    }

    @Test
    @DisplayName("읽기 전용 트랜잭션에서 발행하면 예외가 난다")
    void publishInReadOnlyTransaction() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> eventPublisher.publish(new TestEvent(testId))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(findOutbox()).isEmpty();
    }

    private List<OutboxEvent> findOutbox() {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("TEST") && outbox.getAggregateId().equals(testId))
                .toList();
    }
}
