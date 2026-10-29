package com.backend.global.outbox;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbox 릴레이 정책 값
 */
@Component
@Getter
public class OutboxPolicy {
    // 릴레이가 한 번 실행될 때 발행하는 최대 이벤트 수
    private final int relayBatchSize;

    // 발행 실패를 이 횟수만큼 겪으면 FAILED로 두고 더 이상 발행하지 않는다
    private final int maxRetryCount;

    public OutboxPolicy(
            @Value("${custom.outbox.relayBatchSize}") int relayBatchSize,
            @Value("${custom.outbox.maxRetryCount}") int maxRetryCount
    ) {
        if (relayBatchSize < 1) throw new IllegalArgumentException("custom.outbox.relayBatchSize는 1 이상이어야 합니다: " + relayBatchSize);
        if (maxRetryCount < 1) throw new IllegalArgumentException("custom.outbox.maxRetryCount는 1 이상이어야 합니다: " + maxRetryCount);
        this.relayBatchSize = relayBatchSize;
        this.maxRetryCount = maxRetryCount;
    }
}
