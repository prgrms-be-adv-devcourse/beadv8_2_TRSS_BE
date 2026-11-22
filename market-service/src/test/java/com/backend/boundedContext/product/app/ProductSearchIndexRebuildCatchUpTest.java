package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.search.ProductSearchDocument;
import com.backend.boundedContext.product.out.search.ProductSearchIndexPort;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 전체 재색인 중 다른 트랜잭션이 바꾸고 커밋한 상품을, alias를 옮긴 뒤 새 인덱스에 DB의 최신 값으로 다시 반영하는지 확인한다.
 * 재색인은 관리자·복구 재색인처럼 트랜잭션 없이 불리고 묶음마다 읽기 트랜잭션을 잡는다. 그 사이 판매자 변경은 별도 트랜잭션에서 커밋한다.
 * 보정이 앞서 읽은 상품의 옛 값(판매 중)으로 다시 색인하면 안 되므로, 보정이 커밋된 최신 값을 읽는지 잡는다.
 * 각 트랜잭션이 따로 커밋돼야 하므로 @Transactional을 붙이지 않고, 만든 데이터는 @AfterEach에서 지운다.
 */
@SpringBootTest
class ProductSearchIndexRebuildCatchUpTest {

    private static final long SELLER_ID = 12L;

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductDiscountRepository productDiscountRepository;
    @Autowired private ProductSupport productSupport;
    @Autowired private ProductDiscountSupport productDiscountSupport;
    @Autowired private ProductPolicy productPolicy;
    @Autowired private ProductBehaviorSupport productBehaviorSupport;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductOptionRepository productOptionRepository;
    @Autowired private OptionStockRepository optionStockRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> optionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transaction().executeWithoutResult(status -> {
            for (String name : List.of("보정 테스트 유지 크림", "보정 테스트 중지 크림")) {
                Product product = productRepository.save(Product.create(
                        SELLER_ID, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), name, "설명", 10_000));
                ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
                optionStockRepository.save(OptionStock.init(option, 10));
                productIds.add(product.getId());
                optionIds.add(option.getId());
            }
        });
    }

    @AfterEach
    void tearDown() {
        transaction().executeWithoutResult(status -> {
            optionIds.forEach(productOptionRepository::deleteById);
            productIds.forEach(productRepository::deleteById);
        });
    }

    @Test
    @DisplayName("재색인이 상품을 읽은 뒤 다른 트랜잭션이 그 상품을 중지·커밋하면, alias를 옮긴 뒤 보정이 DB의 최신 값으로 보고 새 인덱스에서 지운다")
    void catchUpReadsChangesCommittedByOtherTransaction() {
        Long kept = productIds.get(0);
        Long stopped = productIds.get(1);
        RecordingIndexPort indexPort = new RecordingIndexPort();
        ProductSearchIndexSupport indexSupport = new ProductSearchIndexSupport(productRepository,
                productDiscountRepository, productSupport, productDiscountSupport, indexPort, productPolicy, productBehaviorSupport, transactionManager);

        // 그 상품이 든 묶음을 새 인덱스에 넣은 직후(재색인 트랜잭션이 상품을 읽은 뒤) 관리자가 중지하고 커밋한다
        TransactionTemplate otherTransaction = transaction();
        otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        indexPort.afterBulkIndexOf(stopped, () -> otherTransaction.executeWithoutResult(status ->
                productRepository.findById(stopped).orElseThrow().stopByAdmin("허위 광고")));

        // 관리자·복구 재색인처럼 트랜잭션 없이 부른다 (ProductFacade.rebuildSearchIndex). 묶음마다 Support가 읽기 트랜잭션을 잡는다
        indexSupport.rebuildIndex();

        assertThat(indexPort.bulkIndexed).contains(kept, stopped);   // 재색인은 중지 전 값(판매 중)으로 넣었다
        List<String> afterSwitch = indexPort.events.subList(indexPort.events.indexOf("switch"), indexPort.events.size());
        assertThat(afterSwitch).contains("delete:" + stopped);       // 보정은 커밋된 중지를 보고 지운다
        assertThat(afterSwitch).doesNotContain("upsert:" + stopped);  // 1차 캐시의 옛 값(판매 중)으로 다시 넣지 않는다
    }

    @Test
    @DisplayName("색인·전체 재색인: 상호명 조회(회원 서비스)와 ES 쓰기는 DB 트랜잭션 밖에서 한다")
    void externalCallsRunOutsideTransaction() {
        List<Boolean> memberCallInTransaction = new ArrayList<>();
        given(memberApi.getSellers(anyCollection())).willAnswer(invocation -> {
            memberCallInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(new SellerDto(SELLER_ID, 1L, "보정 상점", SellerStatus.APPROVED));
        });
        RecordingIndexPort indexPort = new RecordingIndexPort();
        ProductSearchIndexSupport indexSupport = new ProductSearchIndexSupport(productRepository,
                productDiscountRepository, productSupport, productDiscountSupport, indexPort, productPolicy, productBehaviorSupport, transactionManager);

        indexSupport.indexProducts(productIds);
        indexSupport.rebuildIndex();

        assertThat(memberCallInTransaction).isNotEmpty().containsOnly(false);
        assertThat(indexPort.writeInTransaction).isNotEmpty().containsOnly(false);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    /** 새 인덱스에 넣은 상품, alias 전환, 전환 뒤 upsert·delete 순서를 기록하는 색인 포트 */
    private static class RecordingIndexPort implements ProductSearchIndexPort {
        final List<Long> bulkIndexed = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        /** ES 쓰기(upsert·delete·bulkIndex)를 부를 때 DB 트랜잭션이 있었는지 */
        final List<Boolean> writeInTransaction = new ArrayList<>();
        private Long triggerId;
        private Runnable onBulkIndex;

        void afterBulkIndexOf(Long productId, Runnable hook) {
            this.triggerId = productId;
            this.onBulkIndex = hook;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isCurrent() {
            return true;
        }

        @Override
        public void upsert(List<ProductSearchDocument> documents) {
            writeInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            documents.forEach(doc -> events.add("upsert:" + doc.productId()));
        }

        @Override
        public void delete(Collection<Long> productIds) {
            writeInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            productIds.forEach(id -> events.add("delete:" + id));
        }

        @Override
        public String createIndex() {
            return "products_test";
        }

        @Override
        public void bulkIndex(String indexName, List<ProductSearchDocument> documents) {
            writeInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            documents.forEach(doc -> bulkIndexed.add(doc.productId()));
            if (onBulkIndex != null && documents.stream().anyMatch(doc -> doc.productId() == triggerId)) {
                Runnable hook = onBulkIndex;
                onBulkIndex = null;
                hook.run();
            }
        }

        @Override
        public void switchAlias(String indexName) {
            events.add("switch");
        }
    }
}
