package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.search.ProductSearchDocument;
import com.backend.boundedContext.product.out.search.ProductSearchIndexPort;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.BDDMockito.willReturn;

/**
 * 상품 검색 색인: 색인 문서 값(할인가·할인율·품절), 할인 경계 대상, DB 검색 설정의 재색인 거부,
 * 전체 재색인(alias가 없을 때만 복구 재색인, 한 번에 하나만 실행).
 * 재색인 중 다른 트랜잭션이 바꾼 상품의 보정은 테스트 트랜잭션 없이 {@link ProductSearchIndexRebuildCatchUpTest}에서 확인한다.
 * 테스트 설정은 DB 검색(engine=jpa)이라 색인 포트가 아무것도 하지 않으므로, 문서 값은 기록용 포트를 넣은 Support로 확인한다.
 * 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓰고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductSearchIndexTest {

    private static final long MEMBER_ID = 111L;
    private static final long SELLER_ID = 211L;
    private static final long PRICE = 20_000;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductSupport productSupport;

    @Autowired
    private ProductDiscountSupport productDiscountSupport;

    @Autowired
    private ProductPolicy productPolicy;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductDiscountRepository productDiscountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProductBehaviorSupport productBehaviorSupport;

    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private RecordingIndexPort indexPort;
    private ProductSearchIndexSupport indexSupport;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "색인 상점", SellerStatus.APPROVED));
        given(memberApi.getSellers(any()))
                .willReturn(List.of(new SellerDto(SELLER_ID, MEMBER_ID, "색인 상점", SellerStatus.APPROVED)));

        indexPort = new RecordingIndexPort();
        indexSupport = new ProductSearchIndexSupport(productRepository, productDiscountRepository,
                productSupport, productDiscountSupport, indexPort, productPolicy, productBehaviorSupport, transactionManager);
    }

    @Test
    @DisplayName("색인: 진행 중인 할인이 있으면 effectivePrice는 할인가(옵션 추가금 제외), discountRate는 할인율이고, 품절 여부를 함께 담는다")
    void documentUsesSalePrice() {
        Long productId = create();
        createActive(productId, 15_000);

        indexSupport.indexProducts(List.of(productId));

        assertThat(indexPort.upserted).singleElement().satisfies(doc -> {
            assertThat(doc.productId()).isEqualTo(productId);
            assertThat(doc.name()).isEqualTo("색인테스트셔츠");
            assertThat(doc.status()).isEqualTo(ProductSaleStatus.ON_SALE.name());
            assertThat(doc.effectivePrice()).isEqualTo(15_000);
            assertThat(doc.discountRate()).isEqualTo(25);   // 20,000 → 15,000
            assertThat(doc.soldOut()).isFalse();
        });
        assertThat(indexPort.deleted).isEmpty();
    }

    @Test
    @DisplayName("색인: 판매자 상호명을 회원 서비스에서 가져와 담는다")
    void documentIncludesBusinessName() {
        Long productId = create();

        indexSupport.indexProducts(List.of(productId));

        assertThat(indexPort.upserted).singleElement()
                .satisfies(doc -> assertThat(doc.businessName()).isEqualTo("색인 상점"));
    }

    @Test
    @DisplayName("색인: 회원 서비스가 실패해도 상호명 없이(null) 색인한다 (판매 중지·품절·가격 반영이 막히지 않게). 회원에 없는 판매자는 빈 문자열")
    void memberFailureIndexesWithoutBusinessName() {
        Long productId = create();
        given(memberApi.getSellers(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        indexSupport.indexProducts(List.of(productId));
        assertThat(indexPort.upserted).singleElement().satisfies(doc -> assertThat(doc.businessName()).isNull());

        indexPort.upserted.clear();
        willReturn(List.of()).given(memberApi).getSellers(any());   // 회원 서비스에 그 판매자가 없다
        indexSupport.indexProducts(List.of(productId));
        assertThat(indexPort.upserted).singleElement().satisfies(doc -> assertThat(doc.businessName()).isEmpty());
    }

    @Test
    @DisplayName("상호명 채우기: 상호명 없이 색인된 상품을 다시 색인해 상호명을 채운다")
    void fillMissingBusinessNames() {
        Long productId = create();
        indexPort.withoutBusinessName.add(productId);

        assertThat(indexSupport.fillMissingBusinessNames()).isEqualTo(1);
        assertThat(indexPort.upserted).singleElement().satisfies(doc -> {
            assertThat(doc.productId()).isEqualTo(productId);
            assertThat(doc.businessName()).isEqualTo("색인 상점");
        });
    }

    @Test
    @DisplayName("전체 재색인: 회원 서비스가 실패해도 끝까지 색인하고(상호명 없이), 같은 판매자는 재색인 내내 한 번만 부른다")
    void rebuildSurvivesMemberFailure() {
        Long first = create();
        Long second = create();
        indexPort.current = false;
        given(memberApi.getSellers(any())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        indexSupport.rebuildIndex();

        assertThat(indexPort.bulkIndexed).contains(first, second);
        assertThat(indexPort.events).contains("switch");
        verify(memberApi, times(1)).getSellers(any());   // 실패한 판매자를 묶음·보정마다 다시 부르지 않는다
    }

    @Test
    @DisplayName("색인: 찜 수·최근 판매량·행동 점수를 지금 DB 값으로 계산해 담는다 (리뷰가 없으면 리뷰 수·별점 0)")
    void documentIncludesBehaviorMetrics() {
        Long productId = create();
        productFacade.addWishlist(9_101L, productId);
        productFacade.addWishlist(9_102L, productId);
        given(orderApi.getSalesQuantities(any(), any())).willReturn(Map.of(productId, 12L));

        indexSupport.indexProducts(List.of(productId));

        assertThat(indexPort.upserted).singleElement().satisfies(doc -> {
            assertThat(doc.wishCount()).isEqualTo(2);
            assertThat(doc.salesCount30d()).isEqualTo(12);
            assertThat(doc.reviewCount()).isZero();
            assertThat(doc.ratingScore()).isZero();
            assertThat(doc.behaviorScore()).isCloseTo(
                    0.4 * Math.log(3) / Math.log(1_001) + 0.2 * Math.log(13) / Math.log(501), within(1e-9));
        });
    }

    @Test
    @DisplayName("색인: 할인이 없으면 정가·할인율 0, 판매 중이 아니거나 없는 상품은 색인에서 지운다")
    void listPriceAndDeleteNotOnSale() {
        Long onSale = create();
        Long deleted = create();
        productFacade.deleteProduct(MEMBER_ID, deleted);

        indexSupport.indexProducts(List.of(onSale, deleted, -1L));

        assertThat(indexPort.upserted)
                .extracting(ProductSearchDocument::productId, ProductSearchDocument::effectivePrice, ProductSearchDocument::discountRate)
                .containsExactly(tuple(onSale, PRICE, 0));
        assertThat(indexPort.deleted).containsExactlyInAnyOrder(deleted, -1L);
    }

    @Test
    @DisplayName("할인 경계: 기동 후 첫 실행은 돌아보는 구간(5분) 안에 시작했거나 끝난 할인의 상품만 대상이다")
    void discountBoundaryTargetsOnFirstRun() {
        LocalDateTime now = LocalDateTime.now();
        Long started = create();
        Long ended = create();
        Long old = create();
        moveDiscount(createActive(started, 15_000), now.minusMinutes(1), now.plusDays(1));
        moveDiscount(createActive(ended, 15_000), now.minusDays(1), now.minusMinutes(2));
        moveDiscount(createActive(old, 15_000), now.minusDays(1), now.plusDays(1));   // 시작이 구간(5분)보다 이전

        assertThat(indexSupport.findDiscountBoundaryProductIds(null, now))
                .contains(started, ended)
                .doesNotContain(old);
    }

    @Test
    @DisplayName("할인 경계: 지난 성공 실행이 있으면 그 시각 바로 뒤부터만 본다 (구간을 겹치지 않아 같은 경계를 두 번 발행하지 않는다)")
    void discountBoundaryTargetsSinceLastRun() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastRun = now.minusSeconds(10);
        Long justStarted = create();
        Long atLastRun = create();
        Long beforeLastRun = create();
        moveDiscount(createActive(justStarted, 15_000), now.minusSeconds(5), now.plusDays(1));
        moveDiscount(createActive(atLastRun, 15_000), lastRun, now.plusDays(1));                     // 지난 실행 구간의 끝: 이미 발행함
        moveDiscount(createActive(beforeLastRun, 15_000), lastRun.minusSeconds(20), now.plusDays(1)); // 지난 실행 전: 이미 발행함

        assertThat(indexSupport.findDiscountBoundaryProductIds(lastRun, now))
                .contains(justStarted)
                .doesNotContain(atLastRun, beforeLastRun);
    }

    @Test
    @DisplayName("복구 재색인: 인덱스가 최신이면 하지 않고, 아니면(alias 없음·매핑 버전 낮음) 판매 중 상품 전체를 새 인덱스에 색인한 뒤 alias를 옮긴다")
    void rebuildOnlyWhenAliasMissing() {
        Long onSale = create();

        assertThat(indexSupport.rebuildIndexIfOutdated()).isEmpty();
        assertThat(indexPort.events).isEmpty();

        indexPort.current = false;
        assertThat(indexSupport.rebuildIndexIfOutdated()).isPresent();
        assertThat(indexPort.bulkIndexed).contains(onSale);
        assertThat(indexPort.events).first().isEqualTo("create");
        assertThat(indexPort.events).contains("switch");
    }

    @Test
    @DisplayName("전체 재색인은 한 번에 하나만: 실행 중에 관리자 재색인은 409-SEARCH_INDEX_REBUILDING, 복구 재색인은 건너뛴다")
    void rebuildRunsOneAtATime() {
        Long productId = create();
        indexPort.current = false;
        List<Object> duringRebuild = new ArrayList<>();
        indexPort.afterBulkIndexOf(productId, () -> {
            try {
                indexSupport.rebuildIndex();
                duringRebuild.add("rebuilt");
            } catch (DomainException e) {
                duringRebuild.add(e.getResultCode());
            }
            duringRebuild.add(indexSupport.rebuildIndexIfOutdated());
        });

        indexSupport.rebuildIndex();

        assertThat(duringRebuild).containsExactly("409-SEARCH_INDEX_REBUILDING", OptionalInt.empty());
        assertThat(indexPort.events).filteredOn("create"::equals).hasSize(1);

        // 끝나면 다시 실행할 수 있다
        indexSupport.rebuildIndex();
        assertThat(indexPort.events).filteredOn("create"::equals).hasSize(2);
    }

    @Test
    @DisplayName("복구 재색인 보정: 인덱스가 최신이 아님을 처음 본 시각부터 바뀐 상품을 새 인덱스에 다시 반영한다 (재색인 시작보다 이른 변경 포함)")
    void catchUpFromWhenFirstSeenOutdated() {
        Long changedWhileOutdated = create();
        // 인덱스가 10분 전부터 최신이 아니었고, 그사이(5분 전) 바뀐 상품. 재색인 시작 30초 전보다 이르다
        changedMinutesAgo(changedWhileOutdated, 5);
        indexPort.current = false;
        ReflectionTestUtils.setField(indexSupport, "outdatedSince", LocalDateTime.now().minusMinutes(10));

        indexSupport.rebuildIndexIfOutdated();

        assertThat(afterSwitch()).contains("upsert:" + changedWhileOutdated);
        assertThat(ReflectionTestUtils.getField(indexSupport, "outdatedSince")).isNull();   // 재색인이 끝나면 비운다
    }

    @Test
    @DisplayName("복구 재색인 보정: 처음 확인에서 바로 재색인하면 재색인 시작 30초 전부터만 다시 반영한다")
    void catchUpFromRebuildStartWhenJustSeen() {
        Long changedLongAgo = create();
        changedMinutesAgo(changedLongAgo, 5);
        indexPort.current = false;

        indexSupport.rebuildIndexIfOutdated();

        assertThat(indexPort.bulkIndexed).contains(changedLongAgo);   // 재색인 자체에는 들어간다
        assertThat(afterSwitch()).doesNotContain("upsert:" + changedLongAgo);
    }

    @Test
    @DisplayName("재색인 보정: 수정 시각이 바뀌지 않는 변경(찜·리뷰·판매량)으로 재색인 중 컨슈머가 색인한 상품도 alias를 옮긴 뒤 다시 반영한다")
    void catchUpIncludesProductsIndexedWhileRebuilding() {
        Long scanned = create();
        Long wished = create();
        changedMinutesAgo(scanned, 5);
        changedMinutesAgo(wished, 5);   // 수정 시각으로는 보정 대상이 아니다
        // 재색인이 묶음을 넣은 직후 찜 이벤트를 받은 컨슈머가 색인한다 (이전 인덱스로 들어간다)
        indexPort.afterBulkIndexOf(scanned, () -> indexSupport.indexProducts(List.of(wished)));

        indexSupport.rebuildIndex();

        assertThat(afterSwitch()).contains("upsert:" + wished);
        assertThat(afterSwitch()).doesNotContain("upsert:" + scanned);

        // 모은 상품은 그 재색인에서만 쓰고 비운다
        indexPort.events.clear();
        indexSupport.rebuildIndex();
        assertThat(afterSwitch()).doesNotContain("upsert:" + wished);
    }

    /** 상품과 그 옵션·재고의 마지막 변경 시각(updated_at)을 몇 분 전으로 옮긴다 (보정은 이 시각으로 대상을 찾는다) */
    private void changedMinutesAgo(Long productId, int minutes) {
        entityManager.flush();
        String ago = "now() - make_interval(mins => " + minutes + ")";
        entityManager.createNativeQuery("update product set updated_at = " + ago + " where id = :id")
                .setParameter("id", productId).executeUpdate();
        entityManager.createNativeQuery("update product_option set updated_at = " + ago + " where product_id = :id")
                .setParameter("id", productId).executeUpdate();
        entityManager.createNativeQuery("update option_stock set updated_at = " + ago
                        + " where option_id in (select id from product_option where product_id = :id)")
                .setParameter("id", productId).executeUpdate();
    }

    private List<String> afterSwitch() {
        return indexPort.events.subList(indexPort.events.indexOf("switch"), indexPort.events.size());
    }

    @Test
    @DisplayName("복구 재색인: DB 검색 설정이면 아무것도 하지 않는다")
    void rebuildIfOutdatedDisabledWithJpa() {
        assertThat(productFacade.rebuildSearchIndexIfOutdated()).isEmpty();
    }

    @Test
    @DisplayName("전체 재색인: DB 검색 설정이면 409-SEARCH_INDEX_DISABLED")
    void rebuildDisabledWithJpa() {
        assertThatThrownBy(() -> productFacade.rebuildSearchIndex())
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-SEARCH_INDEX_DISABLED");
    }

    private Long create() {
        Long categoryId = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow().getId();
        return productFacade.createProduct(MEMBER_ID, categoryId, "색인테스트셔츠", "설명", PRICE, List.of("색상"),
                List.of(new ProductOptionSpec(List.of("흰색"), 0, 10), new ProductOptionSpec(List.of("검정"), 3_000, 1)),
                THUMBNAIL, List.of(), "정제수");
    }

    /** 시작 전으로 등록한 뒤 시작 시각을 1시간 전으로 옮겨 진행 중인 할인을 만든다 */
    private Long createActive(Long productId, long salePrice) {
        LocalDateTime start = tomorrow();
        Long discountId = productFacade.createDiscount(MEMBER_ID, productId, salePrice, start, start.plusDays(7));
        moveDiscount(discountId, LocalDateTime.now().minusHours(1), start.plusDays(7));
        return discountId;
    }

    private void moveDiscount(Long discountId, LocalDateTime startsAt, LocalDateTime endsAt) {
        ProductDiscount discount = productDiscountRepository.findById(discountId).orElseThrow();
        ReflectionTestUtils.setField(discount, "startsAt", startsAt);
        ReflectionTestUtils.setField(discount, "endsAt", endsAt);
        entityManager.flush();
    }

    private static LocalDateTime tomorrow() {
        return LocalDateTime.now().plusDays(1).withNano(0);
    }

    /** 받은 문서와 지운 ID, 호출 순서를 기록하는 색인 포트 */
    private static class RecordingIndexPort implements ProductSearchIndexPort {
        final List<ProductSearchDocument> upserted = new ArrayList<>();
        final List<Long> deleted = new ArrayList<>();
        final List<Long> bulkIndexed = new ArrayList<>();
        /** create, switch, upsert:{id}, delete:{id} 순서 */
        final List<String> events = new ArrayList<>();
        boolean current = true;
        /** findIdsWithoutBusinessName이 돌려줄 상품 ID */
        final List<Long> withoutBusinessName = new ArrayList<>();
        /** triggerId가 든 묶음을 bulkIndex한 직후 한 번 실행한다 (재색인 중 변경 흉내) */
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
            return current;
        }

        @Override
        public void upsert(List<ProductSearchDocument> documents) {
            upserted.addAll(documents);
            documents.forEach(doc -> events.add("upsert:" + doc.productId()));
        }

        @Override
        public void delete(Collection<Long> productIds) {
            deleted.addAll(productIds);
            productIds.forEach(id -> events.add("delete:" + id));
        }

        @Override
        public String createIndex() {
            events.add("create");
            return "products_test";
        }

        @Override
        public void bulkIndex(String indexName, List<ProductSearchDocument> documents) {
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

        @Override
        public List<Long> findIdsWithoutBusinessName(int limit) {
            return withoutBusinessName;
        }
    }
}
