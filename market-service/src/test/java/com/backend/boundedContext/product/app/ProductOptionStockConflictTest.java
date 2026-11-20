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
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.StockChangeDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옵션 정보 수정(추가금)과 주문 재고 차감이 겹쳐도 서로 영향을 주지 않는지 확인한다.
 *
 * <p>재고가 product_option에 있던 때는 두 문제가 있었다.
 * <ul>
 *   <li>잠금 없이 수정하면 lost update: 옵션 UPDATE에 재고 컬럼이 같이 들어가, 수정 트랜잭션이 읽어 둔 옛 재고로 차감 결과를 덮어썼다.</li>
 *   <li>그래서 옵션 행을 잠그고 수정하면, 수정 트랜잭션이 끝날 때까지 같은 옵션의 주문 차감이 기다렸다.</li>
 * </ul>
 * 재고를 option_stock으로 나눈 뒤에는 옵션 UPDATE에 재고가 없고, 재고 차감은 option_stock 행만 잠그므로 둘 다 생기지 않는다.
 *
 * <p>옵션 삭제와 추가금 수정이 겹치는 경우도 확인한다. 옵션 UPDATE는 모든 컬럼을 쓰므로, 잠금 없이 읽은 수정이 삭제(deleted_at)를 덮어쓰면
 * 지운 옵션이 되살아난다. 수정·삭제는 옵션 행을 쓰기 잠금하고 읽어 서로 순서를 정한다 (TRSS-268).
 *
 * 각 트랜잭션이 따로 커밋돼야 하므로 @Transactional을 붙이지 않고, 만든 데이터는 @AfterEach에서 지운다.
 */
@SpringBootTest
class ProductOptionStockConflictTest {

    private static final long ORDER_ID = 900_003L;
    private static final long ORDER_ITEM_ID = 9_100_000L;
    private static final long SELLER_ID = 10L;
    private static final int INITIAL_STOCK = 10;
    private static final long ADDITIONAL_PRICE = 3_000L;
    /**
     * 옵션 수정 트랜잭션이 옵션 행을 UPDATE한 채 주문 커밋을 기다리는 최대 시간.
     * 차감이 잠금을 기다리지 않으면 바로 끝나므로, 넉넉히 잡아도 정상일 때 테스트 시간은 늘지 않는다
     */
    private static final long EDIT_HOLD_SECONDS = 5;

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

    @Autowired
    private ProductModifyOptionUseCase productModifyOptionUseCase;

    @Autowired
    private ProductDeleteOptionUseCase productDeleteOptionUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private Long productId;
    private Long optionId;
    /** 삭제 충돌 테스트용 상품과 옵션 두 개 (마지막 옵션은 지울 수 없어 색상 옵션 두 개를 만든다) */
    private Long colorProductId;
    private final List<Long> colorOptionIds = new ArrayList<>();

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
            colorOptionIds.forEach(productOptionRepository::deleteById);
            if (colorProductId != null) productRepository.deleteById(colorProductId);
        });
    }

    @Test
    @DisplayName("[lost update] 옵션 추가금을 수정하는 사이 주문이 1개 차감해도, 수정 커밋이 재고를 덮어쓰지 않아 차감이 남는다")
    void optionEditDoesNotOverwriteDeductedStock() throws Exception {
        CountDownLatch editRead = new CountDownLatch(1);
        CountDownLatch deductCommitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 주문: 수정 트랜잭션이 옵션을 읽은 뒤에 1개 차감하고 커밋한다
        Future<?> order = executor.submit(() -> {
            await(editRead);
            deductStock(List.of(new StockChangeDto(ORDER_ID, ORDER_ITEM_ID, optionId, 1)));
            deductCommitted.countDown();
            return null;
        });

        // 판매자 옵션 수정: 옵션과 재고를 읽고(재고 10) → 주문 커밋을 기다린 뒤 → 추가금만 바꿔 커밋한다
        // ProductOption에는 @Version·@DynamicUpdate가 없어 모든 컬럼을 UPDATE하지만, 재고 컬럼은 이제 이 행에 없다
        transactionTemplate.executeWithoutResult(status -> {
            ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
            assertThat(optionStockRepository.findById(optionId).orElseThrow().getStock()).isEqualTo(INITIAL_STOCK);
            editRead.countDown();
            await(deductCommitted);
            option.changeAdditionalPrice(ADDITIONAL_PRICE);
        });

        order.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 차감 이력(-1)과 재고가 맞는다 (분리 전에는 재고가 10으로 되돌아갔다)
        assertThat(deductHistoryCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(additionalPrice()).isEqualTo(ADDITIONAL_PRICE);
    }

    @Test
    @DisplayName("[락 대기] 옵션 수정 트랜잭션이 옵션 행을 UPDATE한 채 커밋 전이어도, 같은 옵션의 주문 차감은 기다리지 않는다")
    void optionEditDoesNotDelayDeduct() throws Exception {
        CountDownLatch editLocked = new CountDownLatch(1);
        CountDownLatch deductCommitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 주문: 수정 트랜잭션이 옵션 행을 잠근(UPDATE) 뒤에 차감하고 커밋한다
        Future<?> order = executor.submit(() -> {
            await(editLocked);
            deductStock(List.of(new StockChangeDto(ORDER_ID, ORDER_ITEM_ID, optionId, 1)));
            deductCommitted.countDown();
            return null;
        });

        // 판매자 옵션 수정: 추가금을 바꿔 UPDATE를 DB에 보내고(옵션 행 잠금), 커밋하지 않은 채 주문 커밋을 기다린다.
        // 차감이 옵션 행 잠금을 기다린다면 이 트랜잭션이 끝날 때까지 커밋할 수 없으므로 대기 시간이 지나 false가 된다.
        // 시간이 아니라 순서(수정 커밋 전에 차감 커밋)로 보므로, 워밍업·GC로 차감이 느려져도 흔들리지 않는다
        boolean deductCommittedWhileEditOpen = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
            option.changeAdditionalPrice(ADDITIONAL_PRICE);
            productOptionRepository.flush();
            editLocked.countDown();
            return awaitQuietly(deductCommitted, EDIT_HOLD_SECONDS);
        }));

        order.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 주문은 수정이 끝나기를 기다리지 않았다 (분리 전에는 수정 커밋 뒤에야 차감이 끝났다)
        assertThat(deductCommittedWhileEditOpen).isTrue();
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(additionalPrice()).isEqualTo(ADDITIONAL_PRICE);
    }

    @Test
    @DisplayName("[삭제·수정 충돌] 옵션 삭제가 커밋되기 전에 같은 옵션의 추가금 수정이 들어오면, 수정은 삭제 커밋을 기다린 뒤 409이고 옵션은 삭제 상태로 남는다")
    void deleteThenModifyKeepsOptionDeleted() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(SELLER_ID,
                    categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "옵션 삭제 충돌 크림", "설명", 10_000, List.of("색상")));
            colorProductId = product.getId();
            for (String color : List.of("흰색", "검정")) {
                ProductOption option = productOptionRepository.save(ProductOption.create(product, List.of(color), 0));
                optionStockRepository.save(OptionStock.init(option, INITIAL_STOCK));
                colorOptionIds.add(option.getId());
            }
        });
        Long targetId = colorOptionIds.get(1);
        CountDownLatch deleteLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 판매자 추가금 수정 (다른 탭): 삭제 트랜잭션이 옵션을 잠근 뒤 같은 옵션을 수정한다
        Future<Throwable> modify = executor.submit(() -> {
            await(deleteLocked);
            try {
                transactionTemplate.executeWithoutResult(status ->
                        productModifyOptionUseCase.modifyOption(SELLER_ID, colorProductId, targetId, ADDITIONAL_PRICE));
                return null;
            } catch (Throwable e) {
                return e;
            }
        });

        // 판매자 옵션 삭제: 지운 뒤 커밋하지 않은 채, 수정 트랜잭션이 행 잠금을 기다리기 시작할 때까지 둔다
        boolean modifyWaited = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            productDeleteOptionUseCase.deleteOption(SELLER_ID, colorProductId, targetId);
            productOptionRepository.flush();
            deleteLocked.countDown();
            return awaitLockWaiter(EDIT_HOLD_SECONDS);
        }));

        Throwable modifyError = modify.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 수정이 실제로 삭제와 겹쳤다 (잠금을 기다렸다)
        assertThat(modifyWaited).isTrue();
        // 잠금 없이 읽었다면 수정이 "삭제 안 됨"을 읽고 deleted_at = null로 덮어써 성공했다
        assertThat(modifyError).isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-OPTION_DELETED");
        ProductOption deleted = productOptionRepository.findById(targetId).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getAdditionalPrice()).isZero();
    }

    /** 다른 세션이 행 잠금을 기다리기 시작하면 true, 시간 안에 없으면 false */
    private boolean awaitLockWaiter(long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting > 0) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return false;
    }

    private int stock() {
        return optionStockRepository.findById(optionId).orElseThrow().getStock();
    }

    private long additionalPrice() {
        return productOptionRepository.findById(optionId).orElseThrow().getAdditionalPrice();
    }

    private long deductHistoryCount() {
        return stockHistoryRepository.findAll().stream()
                .filter(h -> h.getOption().getId().equals(optionId) && h.getChangeType() == StockChangeType.ORDER_DEDUCT)
                .count();
    }

    /** 래치가 열리면 true, 시간 안에 안 열리면 false (예외 없이 다음 단계로 넘어가 트랜잭션을 끝낸다) */
    private static boolean awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
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

    // 재고 차감·복원은 호출한 쪽 트랜잭션 안에서만 부를 수 있다(Propagation.MANDATORY). 주문 트랜잭션처럼 감싸서 부른다
    private void deductStock(List<StockChangeDto> items) {
        transactionTemplate.executeWithoutResult(status -> productFacade.deductStock(items));
    }
}
