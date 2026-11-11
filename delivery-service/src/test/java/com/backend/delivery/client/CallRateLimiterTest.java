package com.backend.delivery.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallRateLimiterTest {

    @Test
    @DisplayName("초당 5회 제한이면 연속 10회 호출이 1.8초 이상 걸린다 (첫 호출은 바로, 나머지 9회는 200ms 간격)")
    void fivePerSecond() {
        CallRateLimiter limiter = new CallRateLimiter(5);

        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            limiter.acquire();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1800));
    }

    @Test
    @DisplayName("여러 스레드가 함께 불러도 합계가 제한을 넘지 않는다")
    void sharedAcrossThreads() throws Exception {
        CallRateLimiter limiter = new CallRateLimiter(10);

        long start = System.nanoTime();
        Thread[] threads = new Thread[2];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 5; i++) limiter.acquire();
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 10회, 100ms 간격 → 첫 호출 뒤 9번 대기
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }
}
