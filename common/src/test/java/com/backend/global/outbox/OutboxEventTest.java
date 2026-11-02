package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.event.EventEnvelope;
import com.backend.shared.event.OutboxTestEvent;

class OutboxEventTest {

    private OutboxEvent pending() {
        return OutboxEvent.create(EventEnvelope.wrap(new OutboxTestEvent(1L)), "{\"testId\":1}");
    }

    @Test
    @DisplayName("실패를 기록하면 재시도 횟수가 오르고, 한도에 닿으면 FAILED가 된다")
    void recordFailure() {
        OutboxEvent outbox = pending();

        assertThat(outbox.recordFailure(2)).isTrue();
        assertThat(outbox.getRetryCount()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        assertThat(outbox.recordFailure(2)).isTrue();
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }

    @Test
    @DisplayName("그사이 다른 릴레이가 발행했으면(PUBLISHED) 실패를 기록하지 않고 FAILED로 덮어쓰지 않는다")
    void recordFailureAfterPublished() {
        OutboxEvent outbox = pending();
        outbox.markPublished(LocalDateTime.now());

        assertThat(outbox.recordFailure(1)).isFalse();
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(outbox.getRetryCount()).isZero();
    }
}
