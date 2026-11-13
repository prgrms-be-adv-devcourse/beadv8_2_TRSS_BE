package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.ProductSearchIndexRequestedEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 상품·할인·품절 여부가 바뀐 트랜잭션이 커밋되면 그 상품을 ES 색인에 반영한다 (engine=es일 때만).
 * 롤백되면 부르지 않는다. 색인은 요청 스레드가 아니라 전용 스레드 하나에서 차례로 하므로,
 * ES가 느리거나 꺼져 있어도 상품 등록·수정·주문 응답은 늦어지지 않는다.
 * 실패하면 로그만 남긴다. 다음 변경이나 관리자 전체 재색인으로 복구한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexEventListener {
    /** 처리를 기다리는 요청 최대 수. 넘치면 버리고 로그를 남긴다 (ES가 오래 멈춘 경우) */
    private static final int QUEUE_CAPACITY = 10_000;

    private final ProductFacade productFacade;
    private final Executor executor;

    @Autowired
    public ProductSearchIndexEventListener(ProductFacade productFacade) {
        this(productFacade, new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "product-search-index");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** 테스트에서 바로 실행하는 Executor를 넣는다 */
    ProductSearchIndexEventListener(ProductFacade productFacade, Executor executor) {
        this.productFacade = productFacade;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIndexRequested(ProductSearchIndexRequestedEvent event) {
        try {
            executor.execute(() -> index(event));
        } catch (RejectedExecutionException e) {
            log.error("상품 검색 색인 대기열이 가득 차 요청을 버렸습니다. 전체 재색인으로 복구해 주세요. productIds={}",
                    event.productIds());
        }
    }

    private void index(ProductSearchIndexRequestedEvent event) {
        try {
            productFacade.indexProducts(event.productIds());
        } catch (Exception e) {
            log.warn("상품 검색 색인에 실패했습니다. 다음 변경이나 전체 재색인으로 복구합니다. productIds={}",
                    event.productIds(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
}
