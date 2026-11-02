package com.backend.global.eventConsumer;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Kafka 컨슈머의 중복 처리 막기. 처리한 이벤트를 processed_event(event_id, consumer)에 기록한다.
 *
 * 컨슈머는 처리할 일과 같은 트랜잭션 안에서 먼저 이 기록을 남긴다. 처리하다 실패하면 기록도 함께 롤백되어 다음에 다시 받는다.
 */
@Component
@RequiredArgsConstructor
public class ProcessedEvents {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 이 컨슈머가 처음 받은 이벤트면 처리했다고 기록하고 true, 이미 처리한 이벤트면 false.
     *
     * @throws IllegalStateException 쓰기 트랜잭션 밖에서 호출한 경우. 기록과 처리가 따로 커밋되면 중복을 막지 못한다
     */
    public boolean markProcessed(UUID eventId, String consumer) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("처리 기록은 처리하는 일과 같은 쓰기 트랜잭션 안에서 남겨야 합니다: " + consumer);
        }
        int inserted = jdbcTemplate.update("""
                insert into processed_event (event_id, consumer, processed_at)
                values (?, ?, ?)
                on conflict (event_id, consumer) do nothing
                """, eventId, consumer, LocalDateTime.now());
        return inserted == 1;
    }
}
