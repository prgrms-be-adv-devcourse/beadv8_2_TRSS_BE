package com.backend.boundedContext.product.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 저장한 추천이 오래됐을 때 응답을 기다리게 하지 않고 뒤에서 다시 계산한다.
 * 같은 회원을 동시에 두 번 계산하지 않고, 대기열이 가득 차면 버린다 (다음 요청이 다시 시도한다).
 */
@Slf4j
@Component
class ProductRecommendRefresher {
    private static final int QUEUE_CAPACITY = 100;

    private final ProductRecommendSupport productRecommendSupport;
    private final Executor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    ProductRecommendRefresher(ProductRecommendSupport productRecommendSupport) {
        this(productRecommendSupport, new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "product-recommend-refresh");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    ProductRecommendRefresher(ProductRecommendSupport productRecommendSupport, Executor executor) {
        this.productRecommendSupport = productRecommendSupport;
        this.executor = executor;
    }

    /** 이미 계산 중인 회원이면 아무것도 하지 않는다 */
    public void refreshLater(Long memberId) {
        if (!inFlight.add(memberId)) return;
        try {
            executor.execute(() -> {
                try {
                    productRecommendSupport.compute(memberId);
                } catch (RuntimeException e) {
                    log.warn("추천을 뒤에서 다시 계산하지 못했습니다. 저장한 결과를 그대로 씁니다. memberId={}", memberId, e);
                } finally {
                    inFlight.remove(memberId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(memberId);
            log.warn("추천 다시 계산 대기열이 가득 차 이번에는 건너뜁니다. memberId={}", memberId);
        }
    }
}
