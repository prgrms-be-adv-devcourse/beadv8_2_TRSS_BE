package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.search.ProductSearchDocument;
import com.backend.boundedContext.product.out.search.ProductSearchIndexPort;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.StockChangeDto;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 상품 검색 색인: 변경 시 색인 요청 이벤트, 색인 문서 값(할인가·품절), 할인 경계 대상, DB 검색 설정의 재색인 거부.
 * 테스트 설정은 DB 검색(engine=jpa)이라 색인 포트가 아무것도 하지 않으므로, 문서 값은 기록용 포트를 넣은 Support로 확인한다.
 * 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓰고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class ProductSearchIndexTest {

    private static final long MEMBER_ID = 111L;
    private static final long SELLER_ID = 211L;
    private static final long PRICE = 20_000;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

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
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private ProductDiscountRepository productDiscountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationEvents events;

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
        indexSupport = new ProductSearchIndexSupport(eventPublisher, productRepository, productDiscountRepository,
                productSupport, productDiscountSupport, indexPort, productPolicy);
    }

    @Test
    @DisplayName("요청: 상품 등록·수정·할인 등록이 그 상품의 색인을 요청한다")
    void requestOnProductAndDiscountChange() {
        Long productId = create();
        productFacade.modifyProduct(MEMBER_ID, productId, "새이름", null, null, null, null, null, List.of());
        LocalDateTime start = tomorrow();
        productFacade.createDiscount(MEMBER_ID, productId, 15_000, start, start.plusDays(7));

        assertThat(requestedIds()).containsExactly(Set.of(productId), Set.of(productId), Set.of(productId));
    }

    @Test
    @DisplayName("요청: 재고 조정·주문 차감은 옵션 품절 여부가 바뀔 때만 색인을 요청한다")
    void requestOnlyWhenSoldOutChanges() {
        Long productId = create();
        ProductOption white = options(productId).get(0);
        events.clear();

        productFacade.adjustStock(MEMBER_ID, productId, white.getId(), -3);   // 10 → 7
        assertThat(requestedIds()).isEmpty();

        productFacade.deductStock(List.of(new StockChangeDto(1L, 9001L, white.getId(), 7)));   // 7 → 0 (품절)
        assertThat(requestedIds()).containsExactly(Set.of(productId));

        productFacade.adjustStock(MEMBER_ID, productId, white.getId(), 5);   // 0 → 5 (품절 풀림)
        assertThat(requestedIds()).containsExactly(Set.of(productId), Set.of(productId));
    }

    @Test
    @DisplayName("색인: 진행 중인 할인이 있으면 effectivePrice는 할인가(옵션 추가금 제외)이고, 품절 여부를 함께 담는다")
    void documentUsesSalePrice() {
        Long productId = create();
        createActive(productId, 15_000);

        indexSupport.indexProducts(List.of(productId));

        assertThat(indexPort.upserted).singleElement().satisfies(doc -> {
            assertThat(doc.productId()).isEqualTo(productId);
            assertThat(doc.name()).isEqualTo("색인테스트셔츠");
            assertThat(doc.status()).isEqualTo(ProductSaleStatus.ON_SALE.name());
            assertThat(doc.effectivePrice()).isEqualTo(15_000);
            assertThat(doc.soldOut()).isFalse();
        });
        assertThat(indexPort.deleted).isEmpty();
    }

    @Test
    @DisplayName("색인: 할인이 없으면 정가, 판매 중이 아니거나 없는 상품은 색인에서 지운다")
    void listPriceAndDeleteNotOnSale() {
        Long onSale = create();
        Long deleted = create();
        productFacade.deleteProduct(MEMBER_ID, deleted);

        indexSupport.indexProducts(List.of(onSale, deleted, -1L));

        assertThat(indexPort.upserted).extracting(ProductSearchDocument::productId, ProductSearchDocument::effectivePrice)
                .containsExactly(tuple(onSale, PRICE));
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
    @DisplayName("할인 경계: 지난 성공 실행이 있으면 그 시각에서 겹침(30초)만큼 앞부터 본다")
    void discountBoundaryTargetsSinceLastRun() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastRun = now.minusSeconds(10);
        Long justStarted = create();
        Long inOverlap = create();
        Long beforeOverlap = create();
        moveDiscount(createActive(justStarted, 15_000), now.minusSeconds(5), now.plusDays(1));
        moveDiscount(createActive(inOverlap, 15_000), lastRun.minusSeconds(20), now.plusDays(1));     // 지난 실행 20초 전: 겹침 안
        moveDiscount(createActive(beforeOverlap, 15_000), lastRun.minusMinutes(1), now.plusDays(1));  // 첫 실행이었다면 대상이지만 이미 처리됨

        assertThat(indexSupport.findDiscountBoundaryProductIds(lastRun, now))
                .contains(justStarted, inOverlap)
                .doesNotContain(beforeOverlap);
    }

    @Test
    @DisplayName("전체 재색인: DB 검색 설정이면 409-SEARCH_INDEX_DISABLED")
    void rebuildDisabledWithJpa() {
        assertThatThrownBy(() -> productFacade.rebuildSearchIndex())
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-SEARCH_INDEX_DISABLED");
    }

    private List<Set<Long>> requestedIds() {
        return events.stream(ProductSearchIndexRequestedEvent.class).map(ProductSearchIndexRequestedEvent::productIds).toList();
    }

    private Long create() {
        Long categoryId = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow().getId();
        return productFacade.createProduct(MEMBER_ID, categoryId, "색인테스트셔츠", "설명", PRICE, List.of("색상"),
                List.of(new ProductOptionSpec(List.of("흰색"), 0, 10), new ProductOptionSpec(List.of("검정"), 3_000, 1)),
                THUMBNAIL, List.of());
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

    private List<ProductOption> options(Long productId) {
        return productOptionRepository.findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(productId);
    }

    private static LocalDateTime tomorrow() {
        return LocalDateTime.now().plusDays(1).withNano(0);
    }

    /** 받은 문서와 지운 ID를 기록하는 색인 포트 */
    private static class RecordingIndexPort implements ProductSearchIndexPort {
        final List<ProductSearchDocument> upserted = new ArrayList<>();
        final List<Long> deleted = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void upsert(List<ProductSearchDocument> documents) {
            upserted.addAll(documents);
        }

        @Override
        public void delete(Collection<Long> productIds) {
            deleted.addAll(productIds);
        }

        @Override
        public String createIndex() {
            return "products_test";
        }

        @Override
        public void bulkIndex(String indexName, List<ProductSearchDocument> documents) {
            upserted.addAll(documents);
        }

        @Override
        public void switchAlias(String indexName) {
        }
    }
}
