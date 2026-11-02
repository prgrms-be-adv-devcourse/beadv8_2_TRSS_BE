package com.backend.global.kafka;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kafka 컨슈머 실패 처리 정책 값
 */
@Component
@Getter
public class KafkaConsumerPolicy {
    // 처음 처리에 실패한 뒤 다시 처리하는 횟수. 이만큼 다시 해도 실패하면 DLT로 보낸다
    private final int maxRetries;

    // 첫 재시도 전 대기 시간(밀리초)
    private final long retryInitialIntervalMillis;

    // 재시도할 때마다 대기 시간에 곱하는 값
    private final double retryMultiplier;

    public KafkaConsumerPolicy(
            @Value("${custom.kafka.consumer.maxRetries}") int maxRetries,
            @Value("${custom.kafka.consumer.retryInitialIntervalMillis}") long retryInitialIntervalMillis,
            @Value("${custom.kafka.consumer.retryMultiplier}") double retryMultiplier
    ) {
        if (maxRetries < 0) throw new IllegalArgumentException("custom.kafka.consumer.maxRetries는 0 이상이어야 합니다: " + maxRetries);
        if (retryInitialIntervalMillis < 1) throw new IllegalArgumentException("custom.kafka.consumer.retryInitialIntervalMillis는 1 이상이어야 합니다: " + retryInitialIntervalMillis);
        if (retryMultiplier < 1) throw new IllegalArgumentException("custom.kafka.consumer.retryMultiplier는 1 이상이어야 합니다: " + retryMultiplier);
        this.maxRetries = maxRetries;
        this.retryInitialIntervalMillis = retryInitialIntervalMillis;
        this.retryMultiplier = retryMultiplier;
    }
}
