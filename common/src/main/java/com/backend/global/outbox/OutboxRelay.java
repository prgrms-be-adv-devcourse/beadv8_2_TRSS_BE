package com.backend.global.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outbox 릴레이. 미발행(PENDING) 이벤트를 저장 순서대로 꺼내 Kafka에 발행한다.
 *
 * 이벤트 하나마다 트랜잭션을 열어 행을 잠근 채 보내고, 브로커가 기록을 마치면(ack) PUBLISHED로 바꾼다.
 * 브로커에 닿지 못하면(KafkaUnavailableException) 재시도 횟수를 쓰지 않고 이번 실행을 멈춘다. 다음 실행에서 다시 보낸다.
 * 그 밖의 실패는 별도 트랜잭션에서 retry_count를 올리고 다음 이벤트로 넘어간다.
 * 보낸 뒤 PUBLISHED로 커밋하기 전에 죽으면 같은 이벤트를 다시 보낼 수 있으므로, 받는 쪽은 eventId로 중복을 거른다(ProcessedEvents).
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxKafkaSender outboxKafkaSender;
    private final OutboxPolicy outboxPolicy;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, OutboxKafkaSender outboxKafkaSender,
                       OutboxPolicy outboxPolicy, PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxKafkaSender = outboxKafkaSender;
        this.outboxPolicy = outboxPolicy;
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
            } catch (KafkaUnavailableException e) {
                // 브로커 문제는 다른 이벤트도 똑같이 실패하므로 이번 실행을 멈춘다. 행은 롤백되어 PENDING 그대로다
                log.warn("Kafka에 닿지 못해 이번 릴레이 실행을 멈춥니다. 다음 실행에서 다시 보냅니다. 남은 이벤트는 PENDING으로 둡니다: {}",
                        e.getMessage());
                break;
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

        outboxKafkaSender.send(outbox);   // 브로커 ack까지 기다린다. 실패하면 이 트랜잭션은 롤백되어 PENDING으로 남는다
        outbox.markPublished(LocalDateTime.now());
        return true;
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
