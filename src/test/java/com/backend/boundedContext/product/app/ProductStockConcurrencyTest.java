package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잠금이 실제로 동작하는지 확인하려면 각 요청이 따로 커밋돼야 하므로 @Transactional을 붙이지 않는다.
 * 대신 테스트가 만든 데이터는 @AfterEach에서 직접 지운다.
 */
@SpringBootTest
class ProductStockConcurrencyTest {

    private static final long ORDER_ID = 900_002L;
    private static final long ORDER_ITEM_ID_BASE = 9_000_000L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long productId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(
                    10L, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "동시성 테스트 크림", "설명", 10_000));
            productId = product.getId();
            optionId = productOptionRepository.save(ProductOption.createDefault(product, 10)).getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            stockHistoryRepository.deleteAll(stockHistoryRepository.findAll().stream()
                    .filter(h -> h.getOption().getId().equals(optionId))
                    .toList());
            productOptionRepository.deleteById(optionId);
            productRepository.deleteById(productId);
        });
    }

    @Test
    @DisplayName("재고 10개에 15명이 동시에 1개씩 주문하면 10명만 성공하고 재고는 0이 된다")
    void concurrentDeduct() throws Exception {
        int requests = 15;
        AtomicInteger success = new AtomicInteger();
        runConcurrently(requests, i -> {
            productFacade.deductStock(List.of(item(ORDER_ITEM_ID_BASE + i, 1)));
            success.incrementAndGet();
        });

        assertThat(success.get()).isEqualTo(10);
        assertThat(stock()).isZero();
        assertThat(historyCount(StockChangeType.ORDER_DEDUCT)).isEqualTo(10);
    }

    @Test
    @DisplayName("같은 주문 항목의 복원이 동시에 와도 예외 없이 한 번만 복원된다")
    void concurrentDuplicateRestore() throws Exception {
        productFacade.deductStock(List.of(item(ORDER_ITEM_ID_BASE, 3)));

        AtomicInteger success = new AtomicInteger();
        runConcurrently(5, i -> {
            productFacade.restoreStock(List.of(item(ORDER_ITEM_ID_BASE, 3)), StockRestoreReason.ORDER_RESTORE);
            success.incrementAndGet();
        });

        assertThat(success.get()).isEqualTo(5);
        assertThat(stock()).isEqualTo(10);
        assertThat(historyCount(StockChangeType.ORDER_RESTORE)).isEqualTo(1);
    }

    @Test
    @DisplayName("먼저 조회해 둔 옵션의 재고가 다른 주문으로 바뀌어도, 차감은 최신 재고 기준으로 한다")
    void deductAfterStaleRead() throws Exception {
        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch otherDeductDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 다른 주문: 첫 번째 주문이 조회를 마친 뒤 4개를 차감하고 커밋한다
        Future<?> other = executor.submit(() -> {
            await(readDone);
            productFacade.deductStock(List.of(item(ORDER_ITEM_ID_BASE + 1, 4)));
            otherDeductDone.countDown();
            return null;
        });

        // 첫 번째 주문: 한 트랜잭션에서 조회(재고 10) → 다른 주문 커밋 → 3개 차감
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(productFacade.getOptions(List.of(optionId)).getFirst().stock()).isEqualTo(10);
            readDone.countDown();
            await(otherDeductDone);
            productFacade.deductStock(List.of(item(ORDER_ITEM_ID_BASE, 3)));
        });

        other.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(stock()).isEqualTo(3);   // 10 - 4 - 3. 옛 값(10)으로 계산하면 7이 된다
    }

    private StockChangeDto item(long orderItemId, int quantity) {
        return new StockChangeDto(ORDER_ID, orderItemId, optionId, quantity);
    }

    private int stock() {
        return productOptionRepository.findById(optionId).orElseThrow().getStock();
    }

    private long historyCount(StockChangeType changeType) {
        return stockHistoryRepository.findAll().stream()
                .filter(h -> h.getOption().getId().equals(optionId) && h.getChangeType() == changeType)
                .count();
    }

    /** 요청 n개를 동시에 출발시키고, 실패한 요청의 예외는 무시한다 (성공 수로 확인) */
    private void runConcurrently(int n, IndexedTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                await(start);
                try {
                    task.run(index);
                } catch (Exception ignored) {
                    // 재고 부족 등은 성공 수로 확인한다
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        executor.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("대기 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index) throws Exception;
    }
}
