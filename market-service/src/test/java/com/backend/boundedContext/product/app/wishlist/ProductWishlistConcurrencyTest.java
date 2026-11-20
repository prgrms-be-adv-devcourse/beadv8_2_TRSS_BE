package com.backend.boundedContext.product.app.wishlist;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 같은 회원이 같은 상품을 동시에 여러 번 찜해도 한 건만 남는지 확인한다.
 * 각 요청이 따로 커밋돼야 하므로 @Transactional을 붙이지 않고, 만든 데이터는 @AfterEach에서 직접 지운다.
 */
@SpringBootTest
class ProductWishlistConcurrencyTest {

    private static final long SELLER_ID = 10L;
    private static final long MEMBER_ID = 7_101L;

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
    private WishlistRepository wishlistRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    private Long productId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(
                    SELLER_ID, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(),
                    "찜 동시성 테스트 크림", "설명", 10_000));
            productId = product.getId();
            optionId = saveOption(product, 10).getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            wishlistRepository.deleteByMemberIdAndProductId(MEMBER_ID, productId);
            outboxEventRepository.deleteAll(wishEvents());
            productOptionRepository.deleteById(optionId);
            productRepository.deleteById(productId);
        });
    }

    @Test
    @DisplayName("같은 상품을 동시에 10번 찜해도 모두 성공하고, 찜은 한 건·찜 추가 이벤트도 한 건만 남는다")
    void concurrentAdd() throws Exception {
        int requests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                productFacade.addWishlist(MEMBER_ID, productId);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);   // 하나라도 예외면 실패
        executor.shutdown();

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(wishlistRepository.existsByMemberIdAndProductId(MEMBER_ID, productId)).isTrue();
            assertThat(wishlistRepository.findAll())
                    .filteredOn(w -> w.getProduct().getId().equals(productId)).hasSize(1);
            assertThat(wishEvents()).extracting(OutboxEvent::getEventType).containsExactly("WishAddedEvent");
        });
    }

    private List<OutboxEvent> wishEvents() {
        return outboxEventRepository.findAll().stream()
                .filter(e -> "PRODUCT".equals(e.getAggregateType()) && e.getAggregateId().equals(productId))
                .toList();
    }

    /** 기본 옵션과 옵션 재고를 함께 저장한다 */
    private ProductOption saveOption(Product product, int stock) {
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        optionStockRepository.save(OptionStock.init(option, stock));
        return option;
    }
}
