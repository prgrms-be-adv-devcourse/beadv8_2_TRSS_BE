package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductOptionDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.ProductSearchPort;
import com.backend.boundedContext.product.out.keyword.PopularKeywordPort;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.shared.review.out.ReviewApi;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 상품 검색·상세를 Facade로 검증한다. 다른 데이터(샘플 상품 등)와 섞이지 않도록 상품명에 고유한 단어(KEY)를 넣고 그 단어로 검색한다.
 * 카테고리는 마이그레이션(V13)이 넣은 기본 카테고리를 쓰고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeSearchTest {

    private static final String KEY = "zqx검색";
    private static final long SELLER_ID = 300L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ReviewApi reviewApi;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OptionStockRepository optionStockRepository;

    @Autowired
    private ProductDiscountRepository productDiscountRepository;

    @Autowired
    private ProductSupport productSupport;

    @Autowired
    private ProductImageSupport productImageSupport;

    @Autowired
    private ProductDiscountSupport productDiscountSupport;

    @Autowired
    private ProductPopularKeywordSupport productPopularKeywordSupport;

    @Autowired
    private ProductSearchPort productSearchPort;

    @Autowired
    private ProductSummarySupport productSummarySupport;

    @Autowired
    private ProductPolicy productPolicy;

    @MockitoBean
    private MemberApi memberApi;

    /** 주문 쪽 구현과 분리하고, 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    private Category skincare;
    private Category makeup;
    private Category top;

    @BeforeEach
    void setUp() {
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(SELLER_ID, 1L, "팔레트 상점", SellerStatus.APPROVED)));
        skincare = category("뷰티", "스킨케어");
        makeup = category("뷰티", "메이크업");
        top = category("의류", "상의");
    }

    @Test
    @DisplayName("상품명 일부로 대소문자 없이 찾고, 상호명·품절 여부를 채운다")
    void searchByKeyword() {
        Product cream = create(skincare, KEY + " Water 크림", 25_000, 10);
        create(skincare, "다른 상품", 25_000, 10);

        PageResponse<ProductSummaryDto> result = search(KEY + " water", null, ProductSort.LATEST, true);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(cream.getId());
            assertThat(item.price()).isEqualTo(25_000);
            assertThat(item.businessName()).isEqualTo("팔레트 상점");
            assertThat(item.soldOut()).isFalse();
        });
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("검색어의 %, _ 는 와일드카드가 아니라 글자로 찾는다")
    void searchKeywordEscape() {
        Product percent = create(skincare, KEY + " 100% 수분", 10_000, 10);
        create(skincare, KEY + " 1000 수분", 10_000, 10);

        assertThat(ids(search(KEY + " 100%", null, ProductSort.LATEST, true))).containsExactly(percent.getId());
        assertThat(ids(search(KEY + " 1_0", null, ProductSort.LATEST, true))).isEmpty();
    }

    @Test
    @DisplayName("대분류로 거르면 그 아래 소분류 전체, 소분류로 거르면 그 소분류만 나온다")
    void searchByCategory() {
        Product cream = create(skincare, KEY + " 크림", 10_000, 10);
        Product tint = create(makeup, KEY + " 틴트", 10_000, 10);
        create(top, KEY + " 티셔츠", 10_000, 10);

        assertThat(ids(search(KEY, skincare.getParent().getId(), ProductSort.LATEST, true)))
                .containsExactlyInAnyOrder(cream.getId(), tint.getId());
        assertThat(ids(search(KEY, makeup.getId(), ProductSort.LATEST, true))).containsExactly(tint.getId());
    }

    @Test
    @DisplayName("특가(DB 검색): discounted=true면 지금 진행 중인 할인이 있는 상품만 나오고, 할인율순은 진행 중 할인의 할인율 높은순(같으면 최신순)이다")
    void searchDiscounted() {
        Product active = create(skincare, KEY + " 진행 중 할인", 10_000, 10);
        Product scheduled = create(skincare, KEY + " 예약 할인", 10_000, 10);
        Product ended = create(skincare, KEY + " 끝난 할인", 10_000, 10);
        Product deleted = create(skincare, KEY + " 지운 할인", 10_000, 10);
        Product none = create(skincare, KEY + " 할인 없음", 10_000, 10);
        LocalDateTime now = LocalDateTime.now();
        Product bigSale = create(skincare, KEY + " 큰 할인", 10_000, 10);
        Product sameRate = create(skincare, KEY + " 같은 할인율", 20_000, 10);
        discount(active, 8_000, now.minusDays(1), now.plusDays(1), false);     // 20%
        discount(bigSale, 5_000, now.minusDays(1), now.plusDays(1), false);    // 50%
        discount(sameRate, 16_000, now.minusDays(1), now.plusDays(1), false);  // 20% (active보다 나중 등록)
        discount(scheduled, 5_000, now.plusDays(1), now.plusDays(2), false);
        discount(ended, 5_000, now.minusDays(2), now.minusDays(1), false);
        discount(deleted, 5_000, now.minusDays(1), now.plusDays(1), true);

        assertThat(ids(searchDiscounted(KEY, ProductSort.LATEST))).containsExactly(sameRate.getId(), bigSale.getId(), active.getId());
        // 할인율 높은순, 같으면 최신순. 진행 중이 아닌 할인(예약·끝남·삭제)과 할인 없는 상품은 0%로 뒤에 최신순
        PageResponse<ProductSummaryDto> byRate = search(KEY, null, ProductSort.DISCOUNT_RATE, true);
        assertThat(ids(byRate)).containsExactly(bigSale.getId(), sameRate.getId(), active.getId(),
                none.getId(), deleted.getId(), ended.getId(), scheduled.getId());
        assertThat(byRate.totalElements()).isEqualTo(7);
        assertThat(searchDiscounted(KEY, ProductSort.DISCOUNT_RATE).totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("없는 카테고리로 거르면 CATEGORY_NOT_FOUND")
    void searchByUnknownCategory() {
        assertThatThrownBy(() -> search(KEY, Long.MAX_VALUE, ProductSort.LATEST, true))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-CATEGORY_NOT_FOUND");
    }

    @Test
    @DisplayName("최신순·가격순으로 정렬하고, 같은 값이면 나중에 등록한 상품(ID 큰 쪽)이 먼저다")
    void searchSort() {
        Product a = create(skincare, KEY + " A", 20_000, 10);
        Product b = create(skincare, KEY + " B", 10_000, 10);
        Product c = create(skincare, KEY + " C", 20_000, 10);

        assertThat(ids(search(KEY, null, ProductSort.LATEST, true))).containsExactly(c.getId(), b.getId(), a.getId());
        assertThat(ids(search(KEY, null, ProductSort.PRICE_ASC, true))).containsExactly(b.getId(), c.getId(), a.getId());
        assertThat(ids(search(KEY, null, ProductSort.PRICE_DESC, true))).containsExactly(c.getId(), a.getId(), b.getId());
    }

    @Test
    @DisplayName("기본은 품절 상품을 빼고, excludeSoldOut=false면 품절 상품도 품절로 표시해 보여 준다")
    void searchExcludeSoldOut() {
        Product inStock = create(skincare, KEY + " 재고 있음", 10_000, 3);
        Product soldOut = create(skincare, KEY + " 품절", 10_000, 1);
        setStock(soldOut, 0);

        assertThat(ids(search(KEY, null, ProductSort.LATEST, true))).containsExactly(inStock.getId());
        assertThat(search(KEY, null, ProductSort.LATEST, false).items())
                .extracting(ProductSummaryDto::productId, ProductSummaryDto::soldOut)
                .containsExactly(
                        tuple(soldOut.getId(), true),
                        tuple(inStock.getId(), false));
    }

    @Test
    @DisplayName("남은 수량은 재고가 1~5개인 상품만 주고, 6개 이상이거나 품절이면 null이다")
    void searchRemainingStock() {
        Product five = create(skincare, KEY + " 5개", 10_000, 5);
        Product one = create(skincare, KEY + " 1개", 10_000, 1);
        Product six = create(skincare, KEY + " 6개", 10_000, 6);
        Product soldOut = create(skincare, KEY + " 품절", 10_000, 1);
        setStock(soldOut, 0);

        assertThat(search(KEY, null, ProductSort.LATEST, false).items())
                .extracting(ProductSummaryDto::productId, ProductSummaryDto::remainingStock)
                .containsExactly(
                        tuple(soldOut.getId(), null),
                        tuple(six.getId(), null),
                        tuple(one.getId(), 1),
                        tuple(five.getId(), 5));
    }

    @Test
    @DisplayName("삭제된 옵션의 재고는 품절 판단에 넣지 않는다")
    void searchIgnoresDeletedOption() {
        Product product = create(skincare, KEY + " 옵션 삭제", 10_000, 5);
        ReflectionTestUtils.setField(option(product), "deletedAt", LocalDateTime.now());

        assertThat(ids(search(KEY, null, ProductSort.LATEST, true))).isEmpty();
    }

    @Test
    @DisplayName("판매 중지·삭제된 상품과 사용 중지된 카테고리의 상품은 검색되지 않는다")
    void searchHidesNotDisplayable() {
        Product onSale = create(skincare, KEY + " 판매 중", 10_000, 10);
        setStatus(create(skincare, KEY + " 중지", 10_000, 10), ProductSaleStatus.STOPPED);
        setStatus(create(skincare, KEY + " 삭제", 10_000, 10), ProductSaleStatus.DELETED);
        create(makeup, KEY + " 사용 중지 카테고리", 10_000, 10);
        makeup.modify(null, false);

        assertThat(ids(search(KEY, null, ProductSort.LATEST, true))).containsExactly(onSale.getId());
        assertThat(search(KEY, makeup.getId(), ProductSort.LATEST, true).items()).isEmpty();
    }

    @Test
    @DisplayName("한 페이지 크기는 최대 50이다")
    void searchMaxPageSize() {
        PageResponse<ProductSummaryDto> result = productFacade.searchProducts(
                KEY, null, ProductSort.LATEST, true, false, PageRequest.of(0, 100));

        assertThat(result.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("상세: 카테고리·상호명·옵션 목록을 주고, 재고가 5개 이하인 옵션만 남은 수량을 준다")
    void getProductDetail() {
        Product product = create(skincare, KEY + " 상세", 25_000, 30);

        ProductDetailDto detail = productFacade.getProductDetail(product.getId());

        assertThat(detail.name()).isEqualTo(KEY + " 상세");
        assertThat(detail.description()).isEqualTo("설명");
        assertThat(detail.price()).isEqualTo(25_000);
        assertThat(detail.parentCategoryName()).isEqualTo("뷰티");
        assertThat(detail.categoryId()).isEqualTo(skincare.getId());
        assertThat(detail.categoryName()).isEqualTo("스킨케어");
        assertThat(detail.businessName()).isEqualTo("팔레트 상점");
        assertThat(detail.thumbnailUrl()).isNull();
        assertThat(detail.imageUrls()).isEmpty();
        assertThat(detail.soldOut()).isFalse();
        assertThat(detail.options()).singleElement()
                .isEqualTo(new ProductOptionDto(option(product).getId(), "기본", List.of(), 0, 25_000, 25_000, false, null));

        setStock(product, 5);
        assertThat(productFacade.getProductDetail(product.getId()).options().getFirst().remainingStock()).isEqualTo(5);

        setStock(product, 0);
        ProductDetailDto soldOut = productFacade.getProductDetail(product.getId());
        assertThat(soldOut.soldOut()).isTrue();
        assertThat(soldOut.options().getFirst()).satisfies(option -> {
            assertThat(option.soldOut()).isTrue();
            assertThat(option.remainingStock()).isNull();
        });
    }

    @Test
    @DisplayName("상세: 찜 수는 지금 찜한 회원 수다 (검색 색인 값이 아니라 바로 센다)")
    void getProductDetailWishCount() {
        Product product = create(skincare, KEY + " 찜 상세", 25_000, 30);
        assertThat(productFacade.getProductDetail(product.getId()).wishCount()).isZero();

        productFacade.addWishlist(9_201L, product.getId());
        productFacade.addWishlist(9_202L, product.getId());

        assertThat(productFacade.getProductDetail(product.getId()).wishCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("목록·상세: 리뷰 수와 평균 별점(소수 첫째 자리)을 지금 DB 값으로 주고, 삭제한 리뷰는 빼며, 리뷰가 없으면 0이다")
    void reviewStatsOnListAndDetail() {
        Product reviewed = create(skincare, KEY + " 리뷰 있음", 25_000, 30);
        Product noReview = create(skincare, KEY + " 리뷰 없음", 25_000, 30);
        review(reviewed, 1, 5);
        review(reviewed, 2, 4);
        review(reviewed, 3, 4);
        review(reviewed, 4, 1).delete(LocalDateTime.now());   // 삭제한 리뷰는 세지 않는다

        assertThat(productFacade.getProductDetail(reviewed.getId()))
                .extracting(ProductDetailDto::reviewCount, ProductDetailDto::averageRating)
                .containsExactly(3L, 4.3);   // 13 / 3 = 4.33
        assertThat(productFacade.getProductDetail(noReview.getId()))
                .extracting(ProductDetailDto::reviewCount, ProductDetailDto::averageRating)
                .containsExactly(0L, 0.0);

        List<ProductSummaryDto> items = search(KEY + " 리뷰", null, ProductSort.LATEST, true).items();
        assertThat(items)
                .extracting(ProductSummaryDto::productId, ProductSummaryDto::reviewCount, ProductSummaryDto::averageRating)
                .contains(tuple(reviewed.getId(), 3L, 4.3), tuple(noReview.getId(), 0L, 0.0));
    }

    private Review review(Product product, long seq, int rating) {
        return reviewRepository.save(Review.create(9_930_000L + product.getId() * 10 + seq, 9_300L + seq, product.getId(),
                1L, "기본", "리뷰어", rating, "좋아요"));
    }

    @Test
    @DisplayName("상세: 없거나, 판매 중지·삭제됐거나, 카테고리가 사용 중지된 상품은 PRODUCT_NOT_FOUND")
    void getProductDetailNotDisplayable() {
        Product stopped = create(skincare, KEY + " 중지", 10_000, 10);
        setStatus(stopped, ProductSaleStatus.STOPPED);
        Product deleted = create(skincare, KEY + " 삭제", 10_000, 10);
        setStatus(deleted, ProductSaleStatus.DELETED);
        Product inactiveCategory = create(makeup, KEY + " 사용 중지 카테고리", 10_000, 10);
        makeup.modify(null, false);

        for (Long productId : List.of(Long.MAX_VALUE, stopped.getId(), deleted.getId(), inactiveCategory.getId())) {
            assertThatThrownBy(() -> productFacade.getProductDetail(productId))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("검색 색인이 늦어 판매 중지·삭제·카테고리 사용 중지·없는 상품의 ID가 와도 DB의 현재 상태로 걸러 판매 중 상품만 돌려준다")
    void searchFiltersStaleIndexResults() {
        Product onSale = create(skincare, KEY + " 판매 중", 10_000, 10);
        Product stopped = create(skincare, KEY + " 중지", 10_000, 10);
        setStatus(stopped, ProductSaleStatus.STOPPED);
        Product deleted = create(skincare, KEY + " 삭제", 10_000, 10);
        setStatus(deleted, ProductSaleStatus.DELETED);
        Product inactiveCategory = create(makeup, KEY + " 사용 중지 카테고리", 10_000, 10);
        makeup.modify(null, false);

        // 색인에는 아직 예전 상태(모두 판매 중)가 남아 있는 상황: 검색 구현이 다섯 개 ID를 정렬 순서대로 돌려준다
        List<Long> staleIds = List.of(stopped.getId(), onSale.getId(), deleted.getId(), inactiveCategory.getId(), Long.MAX_VALUE);
        ProductSearchPort staleIndex = (query, pageable) -> new PageImpl<>(staleIds, pageable, staleIds.size());
        ProductSearchProductsUseCase useCase = new ProductSearchProductsUseCase(
                productSupport, productSummarySupport, staleIndex, productPopularKeywordSupport);

        PageResponse<ProductSummaryDto> result = useCase.searchProducts(KEY, null, ProductSort.LATEST, true, false, PageRequest.of(0, 20));

        assertThat(ids(result)).containsExactly(onSale.getId());
        assertThat(result.totalElements()).isEqualTo(staleIds.size());   // 개수는 검색 구현의 값 그대로
    }

    @Test
    @DisplayName("인기 검색어: 결과가 있는 첫 페이지 검색만 정규화한 검색어로 세고, 다음 페이지·0건·검색어 없음·응답 실패 검색은 세지 않는다")
    void searchRecordsPopularKeyword() {
        create(skincare, KEY + " 크림", 10_000, 10);
        List<String> recorded = new ArrayList<>();
        PopularKeywordPort recordingPort = new PopularKeywordPort() {
            @Override
            public void increase(String key, String label, LocalDateTime at) {
                recorded.add(label);
            }

            @Override
            public List<String> findTop(LocalDateTime lastHour, int hours, double halfLifeHours, double minScore, int limit) {
                return List.of();
            }
        };
        ProductSearchProductsUseCase useCase = new ProductSearchProductsUseCase(
                productSupport, productSummarySupport, productSearchPort,
                new ProductPopularKeywordSupport(recordingPort, productPolicy));

        useCase.searchProducts("  " + KEY.toUpperCase() + " 크림 ", null, null, true, false, PageRequest.of(0, 20));   // 앞뒤 공백, 대문자
        useCase.searchProducts(KEY, null, null, true, false, PageRequest.of(1, 20));          // 다음 페이지
        useCase.searchProducts(KEY + " 없는말", null, null, true, false, PageRequest.of(0, 20));   // 0건
        useCase.searchProducts(null, skincare.getId(), null, true, false, PageRequest.of(0, 20));   // 카테고리만
        given(memberApi.getSellers(anyCollection())).willThrow(new IllegalStateException("member down"));
        assertThatThrownBy(() -> useCase.searchProducts(KEY, null, null, true, false, PageRequest.of(0, 20)));   // 응답 실패

        assertThat(recorded).containsExactly(KEY + " 크림");
    }

    @Test
    @DisplayName("카테고리 트리 상품 수: 판매 중 상품(품절 제외)만 세고, 대분류는 소분류 합이며, 검색 기본값의 결과 개수와 같다")
    void categoryProductCount() {
        long skincareBefore = child("뷰티", "스킨케어").productCount();
        long beautyBefore = root("뷰티").productCount();
        long topBefore = child("의류", "상의").productCount();

        create(skincare, KEY + " 크림", 10_000, 10);
        Product soldOut = create(skincare, KEY + " 토너", 10_000, 10);
        setStock(soldOut, 0);
        setStatus(create(skincare, KEY + " 중지", 10_000, 10), ProductSaleStatus.STOPPED);
        setStatus(create(skincare, KEY + " 삭제", 10_000, 10), ProductSaleStatus.DELETED);
        create(makeup, KEY + " 립", 10_000, 10);
        productRepository.flush();

        CategoryTreeDto beauty = root("뷰티");
        assertThat(child("뷰티", "스킨케어").productCount()).isEqualTo(skincareBefore + 1);
        assertThat(beauty.productCount()).isEqualTo(beautyBefore + 2);
        assertThat(beauty.productCount()).isEqualTo(beauty.children().stream().mapToLong(CategoryTreeDto::productCount).sum());
        assertThat(child("의류", "상의").productCount()).isEqualTo(topBefore);
        assertThat(search(null, skincare.getId(), ProductSort.LATEST, true).totalElements())
                .isEqualTo(child("뷰티", "스킨케어").productCount());
        assertThat(search(null, skincare.getParent().getId(), ProductSort.LATEST, true).totalElements())
                .isEqualTo(beauty.productCount());
    }

    private CategoryTreeDto root(String name) {
        return productFacade.getCategoryTree().stream().filter(root -> root.name().equals(name)).findFirst().orElseThrow();
    }

    private CategoryTreeDto child(String parentName, String name) {
        return root(parentName).children().stream().filter(child -> child.name().equals(name)).findFirst().orElseThrow();
    }

    private PageResponse<ProductSummaryDto> search(String keyword, Long categoryId, ProductSort sort, boolean excludeSoldOut) {
        return productFacade.searchProducts(keyword, categoryId, sort, excludeSoldOut, false, PageRequest.of(0, 20));
    }

    private List<Long> ids(PageResponse<ProductSummaryDto> result) {
        return result.items().stream().map(ProductSummaryDto::productId).toList();
    }

    private PageResponse<ProductSummaryDto> searchDiscounted(String keyword, ProductSort sort) {
        return productFacade.searchProducts(keyword, null, sort, true, true, PageRequest.of(0, 20));
    }

    /** 할인을 등록한 뒤 기간·삭제 여부를 원하는 상태로 옮긴다 (등록은 시작 전 기간만 받으므로) */
    private void discount(Product product, long salePrice, LocalDateTime startsAt, LocalDateTime endsAt, boolean deleted) {
        LocalDateTime now = LocalDateTime.now();
        ProductDiscount discount = productDiscountRepository.save(
                ProductDiscount.create(product, salePrice, now.plusDays(1), now.plusDays(2), now));
        ReflectionTestUtils.setField(discount, "startsAt", startsAt);
        ReflectionTestUtils.setField(discount, "endsAt", endsAt);
        if (deleted) ReflectionTestUtils.setField(discount, "deletedAt", now);
        productDiscountRepository.flush();
    }

    private Product create(Category category, String name, long price, int stock) {
        Product product = productRepository.save(Product.create(SELLER_ID, category, name, "설명", price));
        saveOption(product, stock);
        return product;
    }

    private ProductOption option(Product product) {
        return productOptionRepository.findAll().stream()
                .filter(o -> o.getProduct().getId().equals(product.getId()))
                .findFirst().orElseThrow();
    }

    private void setStock(Product product, int stock) {
        ReflectionTestUtils.setField(optionStock(option(product)), "stock", stock);
    }

    private void setStatus(Product product, ProductSaleStatus status) {
        ReflectionTestUtils.setField(product, "status", status);
    }

    private Category category(String parentName, String name) {
        return categoryRepository.findByParentNameAndName(parentName, name).orElseThrow();
    }

    /** 기본 옵션과 옵션 재고를 함께 저장한다 */
    private ProductOption saveOption(Product product, int stock) {
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        optionStockRepository.save(OptionStock.init(option, stock));
        return option;
    }

    /** 옵션의 재고 행 */
    private OptionStock optionStock(ProductOption option) {
        return optionStockRepository.findById(option.getId()).orElseThrow();
    }
}
