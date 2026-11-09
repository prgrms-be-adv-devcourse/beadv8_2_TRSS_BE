package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.StockChangeDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옵션 정보 수정(이름·추가금)과 주문 재고 차감이 겹쳐도 서로 영향을 주지 않는지 확인한다.
 *
 * <p>재고가 product_option에 있던 때는 두 문제가 있었다.
 * <ul>
 *   <li>잠금 없이 수정하면 lost update: 옵션 UPDATE에 재고 컬럼이 같이 들어가, 수정 트랜잭션이 읽어 둔 옛 재고로 차감 결과를 덮어썼다.</li>
 *   <li>그래서 옵션 행을 잠그고 수정하면, 수정 트랜잭션이 끝날 때까지 같은 옵션의 주문 차감이 기다렸다.</li>
 * </ul>
 * 재고를 option_stock으로 나눈 뒤에는 옵션 UPDATE에 재고가 없고, 재고 차감은 option_stock 행만 잠그므로 둘 다 생기지 않는다.
 *
 * 각 트랜잭션이 따로 커밋돼야 하므로 @Transactional을 붙이지 않고, 만든 데이터는 @AfterEach에서 지운다.
 */
@SpringBootTest
class ProductOptionStockConflictTest {

    private static final long ORDER_ID = 900_003L;
    private static final long ORDER_ITEM_ID = 9_100_000L;
    private static final long SELLER_ID = 10L;
    private static final int INITIAL_STOCK = 10;
    /** 옵션 수정 트랜잭션이 옵션 행을 UPDATE한 채 커밋하지 않고 머무는 시간 (판매자 수정 요청이 느리게 끝나는 상황) */
    private static final long EDIT_HOLD_MILLIS = 1_000;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OptionStockRepository optionStockRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private Long productId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(
                    SELLER_ID, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "옵션 충돌 테스트 크림", "설명", 10_000));
            productId = product.getId();
            optionId = saveOption(product, INITIAL_STOCK).getId();
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
    @DisplayName("[lost update] 옵션 이름을 수정하는 사이 주문이 1개 차감해도, 수정 커밋이 재고를 덮어쓰지 않아 차감이 남는다")
    void optionEditDoesNotOverwriteDeductedStock() throws Exception {
        CountDownLatch editRead = new CountDownLatch(1);
        CountDownLatch deductCommitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 주문: 수정 트랜잭션이 옵션을 읽은 뒤에 1개 차감하고 커밋한다
        Future<?> order = executor.submit(() -> {
            await(editRead);
            productFacade.deductStock(List.of(new StockChangeDto(ORDER_ID, ORDER_ITEM_ID, optionId, 1)));
            deductCommitted.countDown();
            return null;
        });

        // 판매자 옵션 수정: 옵션과 재고를 읽고(재고 10) → 주문 커밋을 기다린 뒤 → 이름만 바꿔 커밋한다
        // ProductOption에는 @Version·@DynamicUpdate가 없어 모든 컬럼을 UPDATE하지만, 재고 컬럼은 이제 이 행에 없다
        transactionTemplate.executeWithoutResult(status -> {
            ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
            assertThat(optionStockRepository.findById(optionId).orElseThrow().getStock()).isEqualTo(INITIAL_STOCK);
            editRead.countDown();
            await(deductCommitted);
            // 옵션 수정 API는 다중 옵션 작업에서 생긴다. 지금은 이름 필드를 직접 바꿔 그 수정을 흉내 낸다
            ReflectionTestUtils.setField(option, "name", "흰색 / M");
        });

        order.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 차감 이력(-1)과 재고가 맞는다 (분리 전에는 재고가 10으로 되돌아갔다)
        assertThat(deductHistoryCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(optionName()).isEqualTo("흰색 / M");
    }

    @Test
    @DisplayName("[락 대기] 옵션 수정 트랜잭션이 옵션 행을 UPDATE한 채 커밋 전이어도, 같은 옵션의 주문 차감은 기다리지 않는다")
    void optionEditDoesNotDelayDeduct() throws Exception {
        CountDownLatch editLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 주문: 수정 트랜잭션이 옵션 행을 잠근(UPDATE) 뒤에 차감을 시도하고, 끝날 때까지 걸린 시간을 잰다
        Future<Long> order = executor.submit(() -> {
            await(editLocked);
            long start = System.nanoTime();
            productFacade.deductStock(List.of(new StockChangeDto(ORDER_ID, ORDER_ITEM_ID, optionId, 1)));
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        });

        // 판매자 옵션 수정: 이름을 바꿔 UPDATE를 DB에 보내고(옵션 행 잠금), 커밋하지 않은 채 잠시 머문다
        transactionTemplate.executeWithoutResult(status -> {
            ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
            ReflectionTestUtils.setField(option, "name", "흰색 / M");
            productOptionRepository.flush();
            editLocked.countDown();
            sleep(EDIT_HOLD_MILLIS);
        });

        long deductMillis = order.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(stock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(optionName()).isEqualTo("흰색 / M");
        // 주문은 수정이 끝나기를 기다리지 않았다 (분리 전에는 잠금을 쥔 시간만큼 기다렸다). 여유를 두고 절반 미만으로 본다
        assertThat(deductMillis).isLessThan(EDIT_HOLD_MILLIS / 2);
    }

    private int stock() {
        return optionStockRepository.findById(optionId).orElseThrow().getStock();
    }

    private String optionName() {
        return productOptionRepository.findById(optionId).orElseThrow().getName();
    }

    private long deductHistoryCount() {
        return stockHistoryRepository.findAll().stream()
                .filter(h -> h.getOption().getId().equals(optionId) && h.getChangeType() == StockChangeType.ORDER_DEDUCT)
                .count();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("대기 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 기본 옵션과 옵션 재고를 함께 저장한다 */
    private ProductOption saveOption(Product product, int stock) {
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        optionStockRepository.save(OptionStock.init(option, stock));
        return option;
    }
}
