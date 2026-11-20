package com.backend.delivery.client;

import java.util.concurrent.TimeUnit;

import com.backend.boundedContext.delivery.out.DeliveryTrackerServiceDownException;

/**
 * 호출 제한기. 의존성 추가 없이 "직전 호출 + 최소 간격"까지 기다리는 방식이다.
 * 스케줄러 여러 개가 같은 클라이언트를 쓰므로, 합계가 초당 maxCallsPerSecond회를 넘지 않는다.
 */
class CallRateLimiter {

    private final long intervalNanos;
    private long nextAllowedNanos = System.nanoTime();

    CallRateLimiter(int maxCallsPerSecond) {
        if (maxCallsPerSecond < 1) throw new IllegalArgumentException("maxCallsPerSecond는 1 이상이어야 합니다: " + maxCallsPerSecond);
        this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / maxCallsPerSecond;
    }

    /** 호출해도 되는 시점까지 기다린다. 기다리는 동안 다른 호출도 줄을 선다(synchronized) */
    synchronized void acquire() {
        long now = System.nanoTime();
        long waitNanos = nextAllowedNanos - now;
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DeliveryTrackerServiceDownException("호출 제한 대기 중 중단되었습니다", e);
            }
        }
        nextAllowedNanos = Math.max(now, nextAllowedNanos) + intervalNanos;
    }
}
