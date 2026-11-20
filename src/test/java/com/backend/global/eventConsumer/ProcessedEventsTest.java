package com.backend.global.eventConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 컨슈머 중복 처리 기록(processed_event)을 실제 DB로 확인한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class ProcessedEventsTest {

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID eventId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from processed_event where event_id = ?", eventId);
    }

    @Test
    @DisplayName("처음 받은 이벤트는 true, 같은 컨슈머가 다시 받으면 false")
    void markProcessed() {
        assertThat(inTransaction(() -> processedEvents.markProcessed(eventId, "settlement"))).isTrue();
        assertThat(inTransaction(() -> processedEvents.markProcessed(eventId, "settlement"))).isFalse();
    }

    @Test
    @DisplayName("컨슈머가 다르면 같은 이벤트도 각자 한 번씩 처리한다")
    void perConsumer() {
        assertThat(inTransaction(() -> processedEvents.markProcessed(eventId, "settlement"))).isTrue();
        assertThat(inTransaction(() -> processedEvents.markProcessed(eventId, "payment"))).isTrue();
    }

    @Test
    @DisplayName("처리하다 실패해 롤백되면 기록도 사라져 다음에 다시 받으면 처리한다")
    void rollbackRemovesRecord() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            processedEvents.markProcessed(eventId, "settlement");
            status.setRollbackOnly();
        });

        assertThat(inTransaction(() -> processedEvents.markProcessed(eventId, "settlement"))).isTrue();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 예외(기록과 처리가 따로 커밋되면 중복을 막지 못함)")
    void requiresTransaction() {
        assertThatThrownBy(() -> processedEvents.markProcessed(eventId, "settlement"))
                .isInstanceOf(IllegalStateException.class);
    }

    private boolean inTransaction(java.util.function.Supplier<Boolean> action) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
