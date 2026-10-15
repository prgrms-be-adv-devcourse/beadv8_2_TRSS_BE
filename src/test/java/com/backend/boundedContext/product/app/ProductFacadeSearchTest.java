package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductOptionDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 상품 검색·상세를 Facade로 검증한다. 다른 데이터(샘플 상품 등)와 섞이지 않도록 상품명에 고유한 단어(KEY)를 넣고 그 단어로 검색한다.
 * 카테고리는 ProductDataInit의 초기 데이터를 쓰고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeSearchTest {

    private static final String KEY = "zqx검색";
    private static final long SELLER_ID = 300L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private MemberApi memberApi;

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
            assertThat(item.categoryId()).isEqualTo(skincare.getId());
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
                KEY, null, ProductSort.LATEST, true, PageRequest.of(0, 100));

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
                .isEqualTo(new ProductOptionDto(option(product).getId(), "기본", false, null));

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

    private PageResponse<ProductSummaryDto> search(String keyword, Long categoryId, ProductSort sort, boolean excludeSoldOut) {
        return productFacade.searchProducts(keyword, categoryId, sort, excludeSoldOut, PageRequest.of(0, 20));
    }

    private List<Long> ids(PageResponse<ProductSummaryDto> result) {
        return result.items().stream().map(ProductSummaryDto::productId).toList();
    }

    private Product create(Category category, String name, long price, int stock) {
        Product product = productRepository.save(Product.create(SELLER_ID, category, name, "설명", price));
        productOptionRepository.save(ProductOption.createDefault(product, stock));
        return product;
    }

    private ProductOption option(Product product) {
        return productOptionRepository.findAll().stream()
                .filter(o -> o.getProduct().getId().equals(product.getId()))
                .findFirst().orElseThrow();
    }

    private void setStock(Product product, int stock) {
        ReflectionTestUtils.setField(option(product), "stock", stock);
    }

    private void setStatus(Product product, ProductSaleStatus status) {
        ReflectionTestUtils.setField(product, "status", status);
    }

    private Category category(String parentName, String name) {
        return categoryRepository.findByParentNameAndName(parentName, name).orElseThrow();
    }
}
