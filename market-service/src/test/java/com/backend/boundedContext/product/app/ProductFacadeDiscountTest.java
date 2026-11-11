package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.app.dto.SellerDiscountDto;
import com.backend.boundedContext.product.app.dto.SellerProductSummaryDto;
import com.backend.boundedContext.product.app.dto.WishlistItemDto;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductDiscountStatus;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 상품 기간 할인: 판매자 등록·수정·삭제·종료, 기간 겹침, 정가 수정 차단, 구매자 조회·주문 스냅샷의 할인가.
 * 판매자는 MemberApi mock으로 준비하고, 테스트마다 롤백된다.
 * 과거 시작은 등록할 수 없으므로, 진행 중인 할인은 시작 전으로 등록한 뒤 시작 시각을 과거로 옮겨 만든다.
 */
@SpringBootTest
@Transactional
class ProductFacadeDiscountTest {

    private static final long MEMBER_ID = 101L;
    private static final long SELLER_ID = 201L;
    private static final long OTHER_MEMBER_ID = 102L;
    private static final long OTHER_SELLER_ID = 202L;
    private static final long BUYER_ID = 301L;
    private static final long PRICE = 20_000;
    private static final String NAME = "할인테스트티셔츠";

    @Autowired
    private ProductFacade productFacade;

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

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
        given(memberApi.getApprovedSellerByMemberId(OTHER_MEMBER_ID))
                .willReturn(new SellerDto(OTHER_SELLER_ID, OTHER_MEMBER_ID, "다른 상점", SellerStatus.APPROVED));
        given(memberApi.getSellers(any()))
                .willReturn(List.of(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED)));
    }

    @Test
    @DisplayName("등록: 시작 전 할인은 수정 폼에 SCHEDULED로 보이고, 구매자에게는 아직 정가다")
    void createScheduled() {
        Long productId = create();
        LocalDateTime start = tomorrow();

        Long discountId = productFacade.createDiscount(MEMBER_ID, productId, 15_000, start, start.plusDays(7));

        assertThat(productFacade.getSellerProductForm(MEMBER_ID, productId).discounts())
                .containsExactly(new SellerDiscountDto(discountId, 15_000, 25, start, start.plusDays(7),
                        ProductDiscountStatus.SCHEDULED));
        ProductDetailDto detail = productFacade.getProductDetail(productId);
        assertThat(detail.salePrice()).isNull();
        assertThat(detail.discountRate()).isNull();
        assertThat(detail.options()).extracting(o -> o.price(), o -> o.listPrice())
                .containsExactly(tuple(20_000L, 20_000L), tuple(23_000L, 23_000L));
    }

    @Test
    @DisplayName("진행 중: 상세·검색·찜·판매자 목록·주문 스냅샷이 할인가(기본가) + 추가금을 쓴다")
    void activeDiscountPrices() {
        Long productId = create();
        Long discountId = createActive(productId, 15_000);
        LocalDateTime endsAt = productDiscountRepository.findById(discountId).orElseThrow().getEndsAt();

        ProductDetailDto detail = productFacade.getProductDetail(productId);
        assertThat(detail.price()).isEqualTo(PRICE);
        assertThat(detail.salePrice()).isEqualTo(15_000);
        assertThat(detail.discountRate()).isEqualTo(25);
        assertThat(detail.discountEndsAt()).isEqualTo(endsAt);
        assertThat(detail.options()).extracting(o -> o.price(), o -> o.listPrice(), o -> o.additionalPrice())
                .containsExactly(tuple(15_000L, 20_000L, 0L), tuple(18_000L, 23_000L, 3_000L));

        List<OptionSnapshotDto> snapshots = productFacade.getOptions(options(productId).stream().map(ProductOption::getId).toList());
        assertThat(snapshots).extracting(OptionSnapshotDto::price, OptionSnapshotDto::listPrice)
                .containsExactlyInAnyOrder(tuple(15_000L, 20_000L), tuple(18_000L, 23_000L));

        ProductSummaryDto summary = productFacade.searchProducts(NAME, null, ProductSort.LATEST, false, PageRequest.of(0, 20))
                .items().stream().filter(item -> item.productId().equals(productId)).findFirst().orElseThrow();
        assertThat(summary.price()).isEqualTo(PRICE);
        assertThat(summary.salePrice()).isEqualTo(15_000);
        assertThat(summary.discountRate()).isEqualTo(25);

        productFacade.addWishlist(BUYER_ID, productId);
        WishlistItemDto wish = productFacade.getWishlist(BUYER_ID, PageRequest.of(0, 20)).items().getFirst();
        assertThat(wish.salePrice()).isEqualTo(15_000);
        assertThat(wish.discountRate()).isEqualTo(25);

        SellerProductSummaryDto sellerItem = productFacade.getSellerProducts(MEMBER_ID, null, NAME, PageRequest.of(0, 20))
                .items().getFirst();
        assertThat(sellerItem.salePrice()).isEqualTo(15_000);
        assertThat(sellerItem.discountRate()).isEqualTo(25);
    }

    @Test
    @DisplayName("종료: 진행 중인 할인을 끝내면 바로 정가로 돌아가고 상태는 ENDED다")
    void endActive() {
        Long productId = create();
        Long discountId = createActive(productId, 15_000);

        productFacade.endDiscount(MEMBER_ID, productId, discountId);

        assertThat(productFacade.getProductDetail(productId).salePrice()).isNull();
        assertThat(productFacade.getSellerProductForm(MEMBER_ID, productId).discounts())
                .extracting(SellerDiscountDto::status).containsExactly(ProductDiscountStatus.ENDED);
        assertCode(() -> productFacade.endDiscount(MEMBER_ID, productId, discountId), "409-DISCOUNT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("기간 겹침: 겹치면 DISCOUNT_PERIOD_OVERLAPPED, 끝과 시작이 맞닿는 건 된다. 지운 할인과는 겹쳐도 된다")
    void overlap() {
        Long productId = create();
        LocalDateTime start = tomorrow();
        Long first = productFacade.createDiscount(MEMBER_ID, productId, 15_000, start, start.plusDays(7));

        assertCode(() -> productFacade.createDiscount(MEMBER_ID, productId, 16_000, start.plusDays(6), start.plusDays(9)),
                "409-DISCOUNT_PERIOD_OVERLAPPED");
        productFacade.createDiscount(MEMBER_ID, productId, 16_000, start.plusDays(7), start.plusDays(9));

        // 수정할 때는 자기 자신과는 겹쳐도 되고, 다른 할인과 겹치면 안 된다
        productFacade.modifyDiscount(MEMBER_ID, productId, first, 14_000, start.plusDays(1), start.plusDays(7));
        assertCode(() -> productFacade.modifyDiscount(MEMBER_ID, productId, first, 14_000, start, start.plusDays(8)),
                "409-DISCOUNT_PERIOD_OVERLAPPED");

        productFacade.deleteDiscount(MEMBER_ID, productId, first);
        productFacade.createDiscount(MEMBER_ID, productId, 17_000, start, start.plusDays(7));
    }

    @Test
    @DisplayName("수정·삭제: 시작 전만 되고, 진행 중이면 DISCOUNT_NOT_SCHEDULED. 지운 할인은 DISCOUNT_NOT_FOUND")
    void modifyAndDelete() {
        Long productId = create();
        LocalDateTime start = tomorrow();
        Long scheduled = productFacade.createDiscount(MEMBER_ID, productId, 15_000, start, start.plusDays(7));

        productFacade.modifyDiscount(MEMBER_ID, productId, scheduled, 10_000, start.plusDays(1), start.plusDays(2));
        assertThat(productFacade.getSellerProductForm(MEMBER_ID, productId).discounts())
                .extracting(SellerDiscountDto::salePrice, SellerDiscountDto::discountRate, SellerDiscountDto::startsAt)
                .containsExactly(tuple(10_000L, 50, start.plusDays(1)));

        productFacade.deleteDiscount(MEMBER_ID, productId, scheduled);
        assertThat(productFacade.getSellerProductForm(MEMBER_ID, productId).discounts()).isEmpty();
        assertCode(() -> productFacade.deleteDiscount(MEMBER_ID, productId, scheduled), "404-DISCOUNT_NOT_FOUND");

        Long active = createActive(productId, 15_000);
        assertCode(() -> productFacade.modifyDiscount(MEMBER_ID, productId, active, 14_000, start, start.plusDays(1)),
                "409-DISCOUNT_NOT_SCHEDULED");
        assertCode(() -> productFacade.deleteDiscount(MEMBER_ID, productId, active), "409-DISCOUNT_NOT_SCHEDULED");
    }

    @Test
    @DisplayName("검증: 할인가가 정가의 10~99%가 아니거나, 시작이 과거거나, 1년을 넘으면 400")
    void validation() {
        Long productId = create();
        LocalDateTime start = tomorrow();

        assertCode(() -> productFacade.createDiscount(MEMBER_ID, productId, 1_999, start, start.plusDays(1)),
                "400-DISCOUNT_PRICE_INVALID");
        assertCode(() -> productFacade.createDiscount(MEMBER_ID, productId, 19_801, start, start.plusDays(1)),
                "400-DISCOUNT_PRICE_INVALID");
        assertCode(() -> productFacade.createDiscount(MEMBER_ID, productId, 15_000, LocalDateTime.now().minusMinutes(2),
                start), "400-DISCOUNT_PERIOD_INVALID");
        assertCode(() -> productFacade.createDiscount(MEMBER_ID, productId, 15_000, start, start.plusYears(1).plusSeconds(1)),
                "400-DISCOUNT_PERIOD_INVALID");
        productFacade.createDiscount(MEMBER_ID, productId, 2_000, start, start.plusYears(1));
    }

    @Test
    @DisplayName("정가 수정: 진행 중이거나 예약된 할인이 있으면 PRICE_LOCKED_BY_DISCOUNT, 같은 가격이나 다른 항목은 된다")
    void priceLockedByDiscount() {
        Long productId = create();
        Long discountId = productFacade.createDiscount(MEMBER_ID, productId, 15_000, tomorrow(), tomorrow().plusDays(1));

        assertCode(() -> modifyPrice(productId, 25_000), "409-PRICE_LOCKED_BY_DISCOUNT");
        modifyPrice(productId, PRICE);
        productFacade.modifyProduct(MEMBER_ID, productId, "새 이름", null, null, null, null, null, List.of());

        productFacade.deleteDiscount(MEMBER_ID, productId, discountId);
        modifyPrice(productId, 25_000);
        assertThat(productRepository.findById(productId)).get().extracting(p -> p.getPrice()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("정가 수정: 진행 중인 할인을 끝내면 가격을 바꿀 수 있다")
    void priceAfterEnd() {
        Long productId = create();
        Long discountId = createActive(productId, 15_000);
        assertCode(() -> modifyPrice(productId, 25_000), "409-PRICE_LOCKED_BY_DISCOUNT");

        productFacade.endDiscount(MEMBER_ID, productId, discountId);
        modifyPrice(productId, 25_000);
    }

    @Test
    @DisplayName("권한·상태: 다른 판매자의 상품은 PRODUCT_NOT_FOUND, 판매 중지 상품은 PRODUCT_NOT_EDITABLE")
    void ownershipAndStatus() {
        Long productId = create();
        LocalDateTime start = tomorrow();
        Long discountId = productFacade.createDiscount(MEMBER_ID, productId, 15_000, start, start.plusDays(1));

        assertCode(() -> productFacade.createDiscount(OTHER_MEMBER_ID, productId, 15_000, start.plusDays(2), start.plusDays(3)),
                "404-PRODUCT_NOT_FOUND");
        assertCode(() -> productFacade.deleteDiscount(OTHER_MEMBER_ID, productId, discountId), "404-PRODUCT_NOT_FOUND");
        assertCode(() -> productFacade.deleteDiscount(MEMBER_ID, productId, Long.MAX_VALUE), "404-DISCOUNT_NOT_FOUND");

        ReflectionTestUtils.setField(productRepository.findById(productId).orElseThrow(), "status", ProductSaleStatus.STOPPED);
        entityManager.flush();   // 할인 기능은 상품을 잠근 뒤 DB 값으로 다시 읽으므로 먼저 반영한다
        assertCode(() -> productFacade.createDiscount(MEMBER_ID, productId, 15_000, start.plusDays(2), start.plusDays(3)),
                "409-PRODUCT_NOT_EDITABLE");
        assertCode(() -> productFacade.deleteDiscount(MEMBER_ID, productId, discountId), "409-PRODUCT_NOT_EDITABLE");
    }

    /** 옵션 2개(추가금 0, 3,000)인 정가 20,000원 상품 */
    private Long create() {
        return productFacade.createProduct(MEMBER_ID, skincareId(), NAME, "설명", PRICE, List.of("색상"),
                List.of(new ProductOptionSpec(List.of("흰색"), 0, 10), new ProductOptionSpec(List.of("검정"), 3_000, 10)),
                THUMBNAIL, List.of());
    }

    /** 시작 전으로 등록한 뒤 시작 시각을 1시간 전으로 옮겨 진행 중인 할인을 만든다 */
    private Long createActive(Long productId, long salePrice) {
        LocalDateTime start = tomorrow();
        Long discountId = productFacade.createDiscount(MEMBER_ID, productId, salePrice, start, start.plusDays(7));
        ProductDiscount discount = productDiscountRepository.findById(discountId).orElseThrow();
        ReflectionTestUtils.setField(discount, "startsAt", LocalDateTime.now().minusHours(1));
        entityManager.flush();
        return discountId;
    }

    private void modifyPrice(Long productId, long price) {
        productFacade.modifyProduct(MEMBER_ID, productId, null, price, null, null, null, null, List.of());
    }

    private List<ProductOption> options(Long productId) {
        return productOptionRepository.findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(productId);
    }

    private static LocalDateTime tomorrow() {
        return LocalDateTime.now().plusDays(1).withNano(0);
    }

    private Long skincareId() {
        return categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow().getId();
    }

    private static void assertCode(ThrowingCallable call, String resultCode) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(resultCode);
    }
}
