package com.backend.global.outbox;

import com.backend.shared.event.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outbox 릴레이. 미발행(PENDING) 이벤트를 저장 순서대로 꺼내 스프링 이벤트로 발행한다.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventTypes outboxEventTypes;
    private final OutboxPolicy outboxPolicy;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, OutboxEventTypes outboxEventTypes,
                       OutboxPolicy outboxPolicy, ApplicationEventPublisher applicationEventPublisher,
                       ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventTypes = outboxEventTypes;
        this.outboxPolicy = outboxPolicy;
        this.applicationEventPublisher = applicationEventPublisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 미발행 이벤트를 최대 relayBatchSize개 발행한다.
     *
     * @return 발행에 성공한 이벤트 수
     */
    public int relayPending() {
        long afterId = 0;
        int published = 0;
        for (int i = 0; i < outboxPolicy.getRelayBatchSize(); i++) {
            long cursor = afterId;
            AtomicReference<OutboxEvent> current = new AtomicReference<>();
            try {
                boolean found = Boolean.TRUE.equals(transactionTemplate.execute(status -> relayNext(cursor, current)));
                if (!found) break;
                published++;
            } catch (RuntimeException e) {
                if (current.get() == null) throw e;   // 이벤트를 꺼내기 전 실패(DB 연결 등)는 스케줄러가 로그를 남기고 다음 실행에서 다시 한다
                recordFailure(current.get(), e);
            }
            afterId = current.get().getId();   // 실패한 이벤트는 이번 실행에서 다시 시도하지 않는다. 같은 대상의 뒤 이벤트는 쿼리가 막는다
        }
        return published;
    }

    private boolean relayNext(long afterId, AtomicReference<OutboxEvent> current) {
        OutboxEvent outbox = outboxEventRepository.findNextPendingForUpdate(afterId).orElse(null);
        if (outbox == null) return false;
        current.set(outbox);

        applicationEventPublisher.publishEvent(toEvent(outbox));
        outbox.markPublished(LocalDateTime.now());
        return true;
    }

    private DomainEvent toEvent(OutboxEvent outbox) {
        Class<? extends DomainEvent> type = outboxEventTypes.find(outbox.getEventType())
                .orElseThrow(() -> new IllegalStateException("알 수 없는 이벤트 타입: " + outbox.getEventType()));
        return objectMapper.readValue(outbox.getPayload(), type);
    }

    private void recordFailure(OutboxEvent failed, RuntimeException cause) {
        transactionTemplate.executeWithoutResult(status ->
                outboxEventRepository.findByIdForUpdate(failed.getId()).ifPresent(outbox -> {
                    if (!outbox.recordFailure(outboxPolicy.getMaxRetryCount())) {
                        log.info("발행 실패를 기록하려 했지만 그사이 다른 릴레이가 처리했습니다. eventId={}, status={}",
                                outbox.getEventId(), outbox.getStatus());
                        return;
                    }
                    if (outbox.getStatus() == OutboxEventStatus.FAILED) {
                        log.error("이벤트 발행이 {}번 실패해 FAILED로 둡니다. eventId={}, eventType={}",
                                outbox.getRetryCount(), outbox.getEventId(), outbox.getEventType(), cause);
                    } else {
                        log.warn("이벤트 발행에 실패했습니다({}번째). 다음 실행에서 다시 시도합니다. eventId={}, eventType={}",
                                outbox.getRetryCount(), outbox.getEventId(), outbox.getEventType(), cause);
                    }
                }));
    }
}
