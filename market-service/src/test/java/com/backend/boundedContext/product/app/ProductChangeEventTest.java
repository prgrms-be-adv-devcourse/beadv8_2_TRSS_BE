package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.event.ProductChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 상품 변경 이벤트(ProductChangedEvent)가 상품을 바꾼 트랜잭션에서 outbox에 저장되는지.
 * AI 추천과 검색 색인이 같은 이벤트를 받으므로, 상품 내용·판매 상태뿐 아니라 품절 여부·옵션·할인·할인 경계·카테고리 변경에서도 발행한다.
 * 카테고리는 마이그레이션이 넣은 기본 카테고리를 쓴다. 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductChangeEventTest {

    private static final long MEMBER_ID = 100L;
    private static final long SELLER_ID = 200L;
    private static final String EVENT_TYPE = "ProductChangedEvent";

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private ProductDiscountRepository productDiscountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
    }

    @Test
    @DisplayName("뷰티 상품 등록: 이름·설명·소분류·전성분·정가·대표 이미지·판매 중 여부를 담아 product.events로 발행한다")
    void create() {
        Long productId = createBeauty("수분 크림", "정제수, 나이아신아마이드");

        ProductChangedEvent event = lastEvent(productId);
        OutboxEvent outbox = events(productId).getLast();
        assertThat(outbox.getAggregateType()).isEqualTo("PRODUCT");
        assertThat(outbox.getAggregateId()).isEqualTo(productId);
        assertThat(event.name()).isEqualTo("수분 크림");
        assertThat(event.description()).isEqualTo("설명");
        assertThat(event.categoryName()).isEqualTo("스킨케어");
        assertThat(event.beauty()).isTrue();
        assertThat(event.ingredientsText()).isEqualTo("정제수, 나이아신아마이드");
        assertThat(event.price()).isEqualTo(25_000);
        assertThat(event.thumbnailUrl()).isNotBlank();
        assertThat(event.onSale()).isTrue();
        assertThat(event.soldOut()).isFalse();
    }

    @Test
    @DisplayName("의류 상품 등록: beauty=false, 전성분 null")
    void createClothing() {
        Long productId = productFacade.createProduct(MEMBER_ID, categoryId("의류", "상의"), "티셔츠", "설명", 20_000,
                List.of(), List.of(ProductOptionSpec.single(10)), THUMBNAIL, List.of(), "정제수");

        ProductChangedEvent event = lastEvent(productId);
        assertThat(event.beauty()).isFalse();
        assertThat(event.ingredientsText()).isNull();
    }

    @Test
    @DisplayName("수정하면 바뀐 값으로, 삭제하면 onSale=false로 한 번씩 더 발행한다")
    void modifyAndDelete() {
        Long productId = createBeauty("수분 크림", "정제수");

        productFacade.modifyProduct(MEMBER_ID, productId, "진정 크림", null, null, null, null, null, List.of(), "정제수, 병풀추출물");
        assertThat(lastEvent(productId).name()).isEqualTo("진정 크림");
        assertThat(lastEvent(productId).ingredientsText()).isEqualTo("정제수, 병풀추출물");

        productFacade.deleteProduct(MEMBER_ID, productId);
        assertThat(lastEvent(productId).onSale()).isFalse();
        assertThat(events(productId)).hasSize(3);
    }

    @Test
    @DisplayName("관리자 판매 중지는 onSale=false로 발행한다")
    void stopByAdmin() {
        Long productId = createBeauty("수분 크림", "정제수");

        productFacade.stopProductByAdmin(productId, "부적절한 상품");

        assertThat(lastEvent(productId).onSale()).isFalse();
    }

    @Test
    @DisplayName("재고: 품절 여부가 바뀔 때만 발행하고 soldOut을 담는다 (주문 차감·복원, 판매자 조정)")
    void publishOnlyWhenSoldOutChanges() {
        Long productId = createBeauty("수분 크림", "정제수");   // 재고 10
        ProductOption option = options(productId).getFirst();

        productFacade.adjustStock(MEMBER_ID, productId, option.getId(), -3);   // 10 → 7
        assertThat(events(productId)).hasSize(1);   // 등록 때 한 번뿐

        productFacade.deductStock(List.of(new StockChangeDto(1L, 9001L, option.getId(), 7)));   // 7 → 0 (품절)
        assertThat(events(productId)).hasSize(2);
        assertThat(lastEvent(productId).soldOut()).isTrue();

        productFacade.restoreStock(List.of(new StockChangeDto(1L, 9001L, option.getId(), 7)), StockRestoreReason.ORDER_RESTORE);   // 0 → 7
        assertThat(events(productId)).hasSize(3);
        assertThat(lastEvent(productId).soldOut()).isFalse();
    }

    @Test
    @DisplayName("옵션 추가·삭제와 할인 등록·지금 종료가 발행한다 (검색 색인의 품절·가격·할인율이 바뀔 수 있다)")
    void publishOnOptionAndDiscountChange() {
        Long productId = productFacade.createProduct(MEMBER_ID, categoryId("뷰티", "스킨케어"), "수분 크림", "설명", 25_000,
                List.of("용량"), List.of(new ProductOptionSpec(List.of("50ml"), 0, 10)), THUMBNAIL, List.of(), "정제수");

        Long added = productFacade.addOption(MEMBER_ID, productId, new ProductOptionSpec(List.of("100ml"), 5_000, 3));
        productFacade.deleteOption(MEMBER_ID, productId, added);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);
        Long discountId = productFacade.createDiscount(MEMBER_ID, productId, 20_000, start, start.plusDays(7));
        moveDiscountStart(discountId, LocalDateTime.now().minusHours(1));   // 진행 중으로 옮긴다
        productFacade.endDiscount(MEMBER_ID, productId, discountId);

        assertThat(events(productId)).hasSize(5);   // 등록 + 옵션 추가·삭제 + 할인 등록·종료
    }

    @Test
    @DisplayName("할인 경계: 할인이 시작했거나 끝난 상품의 이벤트를 발행한다 (할인 경계 스케줄러)")
    void publishOnDiscountBoundary() {
        Long productId = createBeauty("수분 크림", "정제수");
        LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);
        Long discountId = productFacade.createDiscount(MEMBER_ID, productId, 20_000, start, start.plusDays(7));
        moveDiscountStart(discountId, LocalDateTime.now().minusMinutes(1));   // 방금 시작했다
        int before = events(productId).size();

        int published = productFacade.publishDiscountBoundaryChanges(null, LocalDateTime.now());

        assertThat(published).isGreaterThanOrEqualTo(1);
        assertThat(events(productId)).hasSize(before + 1);
    }

    @Test
    @DisplayName("소분류를 사용 중지하면 그 소분류 상품을 onSale=false로 발행한다 (AI 추천에서 빠지게)")
    void publishOnCategoryDeactivation() {
        Long productId = createBeauty("수분 크림", "정제수");

        productFacade.modifyCategory(categoryId("뷰티", "스킨케어"), null, false);

        assertThat(events(productId)).hasSize(2);
        assertThat(lastEvent(productId).onSale()).isFalse();
    }

    @Test
    @DisplayName("소분류 수정 요청이 지금 값과 같으면(이름·사용 여부 그대로) 발행하지 않는다")
    void noPublishWhenCategoryUnchanged() {
        Long productId = createBeauty("수분 크림", "정제수");
        Long skincare = categoryId("뷰티", "스킨케어");

        productFacade.modifyCategory(skincare, "스킨케어", true);   // 관리자 화면이 지금 값을 그대로 보낸 경우

        assertThat(events(productId)).hasSize(1);   // 등록 때 한 번뿐
    }

    @Test
    @DisplayName("전체 재발행: 판매 중인 상품만 한 번씩 발행하고 그 수를 돌려준다")
    void publishAll() {
        Long onSale = createBeauty("수분 크림", "정제수");
        Long stopped = createBeauty("진정 크림", "정제수");
        productFacade.stopProductByAdmin(stopped, "사유");
        int before = events(onSale).size();

        int published = productFacade.publishAllProductChanges();

        assertThat(published).isGreaterThanOrEqualTo(1);
        assertThat(events(onSale)).hasSize(before + 1);
        assertThat(events(stopped)).hasSize(2);   // 등록·중지 때뿐, 재발행에는 빠진다
    }

    private Long createBeauty(String name, String ingredientsText) {
        return productFacade.createProduct(MEMBER_ID, categoryId("뷰티", "스킨케어"), name, "설명", 25_000,
                List.of(), List.of(ProductOptionSpec.single(10)), THUMBNAIL, List.of(), ingredientsText);
    }

    @Test
    @DisplayName("판매 지표: 판매량이 바뀐 상품마다 ProductMetricsChangedEvent(상품 ID만)를 product.events로 발행한다")
    void publishSalesMetricsChanges() {
        Long productId = createBeauty("수분 크림", "정제수");
        given(orderApi.findProductIdsWithSalesChanged(any(), any())).willReturn(List.of(productId, productId));   // 중복은 한 번만

        int published = productFacade.publishSalesMetricsChanges(LocalDateTime.now().minusMinutes(1), LocalDateTime.now());

        assertThat(published).isEqualTo(1);
        List<OutboxEvent> metrics = outboxEventRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("ProductMetricsChangedEvent") && e.getAggregateId().equals(productId))
                .toList();
        assertThat(metrics).singleElement().satisfies(e -> assertThat(e.getAggregateType()).isEqualTo("PRODUCT"));
        assertThat(events(productId)).hasSize(1);   // 상품 변경 이벤트(AI용)는 등록 때뿐
    }

    private List<ProductOption> options(Long productId) {
        return productOptionRepository.findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(productId);
    }

    /** 할인은 시작 전으로만 등록되므로 시작 시각을 옮겨 진행 중으로 만든다 */
    private void moveDiscountStart(Long discountId, LocalDateTime startsAt) {
        ProductDiscount discount = productDiscountRepository.findById(discountId).orElseThrow();
        ReflectionTestUtils.setField(discount, "startsAt", startsAt);
        productDiscountRepository.flush();
    }

    private List<OutboxEvent> events(Long productId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getEventType().equals(EVENT_TYPE) && e.getAggregateId().equals(productId))
                .sorted(Comparator.comparing(OutboxEvent::getId))
                .toList();
    }

    private ProductChangedEvent lastEvent(Long productId) {
        return objectMapper.readValue(events(productId).getLast().getPayload(), ProductChangedEvent.class);
    }

    private Long categoryId(String parentName, String childName) {
        CategoryTreeDto parent = productFacade.getCategoryTree().stream()
                .filter(c -> c.name().equals(parentName)).findFirst().orElseThrow();
        return parent.children().stream().filter(c -> c.name().equals(childName)).findFirst().orElseThrow().categoryId();
    }
}
