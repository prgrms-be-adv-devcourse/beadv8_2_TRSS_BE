package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.app.dto.SellerProductListDto;
import com.backend.boundedContext.product.app.dto.SellerProductSummaryDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductStoppedBy;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.exception.DomainException;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 판매자 본인 상품 목록·수정 폼·수정을 Facade로 검증한다.
 * 다른 데이터와 섞이지 않도록 이 테스트만 쓰는 판매자 ID로 상품을 만들고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeSellerProductTest {

    private static final long MEMBER_ID = 500L;
    private static final long SELLER_ID = 9_500L;
    private static final long OTHER_SELLER_ID = 9_501L;

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

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
        makeup = categoryRepository.findByParentNameAndName("뷰티", "메이크업").orElseThrow();
    }

    @Test
    @DisplayName("목록: 본인 상품만 최신순으로, 삭제된 상품은 빼고 옵션 ID·재고·상태·중지 사유를 채운다")
    void getSellerProducts() {
        Product onSale = create(SELLER_ID, "판매 중 크림", 10);
        Product stopped = create(SELLER_ID, "중지된 크림", 3);
        stop(stopped, "성분 표기 오류");
        setStatus(create(SELLER_ID, "삭제된 크림", 10), ProductSaleStatus.DELETED);
        create(OTHER_SELLER_ID, "다른 판매자 크림", 10);

        SellerProductListDto result = getList(null, null);

        assertThat(result.products().items())
                .extracting(SellerProductSummaryDto::productId)
                .containsExactly(stopped.getId(), onSale.getId());
        assertThat(result.products().items().getLast()).satisfies(item -> {
            assertThat(item.optionId()).isEqualTo(option(onSale).getId());
            assertThat(item.name()).isEqualTo("판매 중 크림");
            assertThat(item.categoryName()).isEqualTo("스킨케어");
            assertThat(item.stock()).isEqualTo(10);
            assertThat(item.status()).isEqualTo(ProductDisplayStatus.ON_SALE);
            assertThat(item.stoppedReason()).isNull();
        });
        assertThat(result.products().items().getFirst()).satisfies(item -> {
            assertThat(item.status()).isEqualTo(ProductDisplayStatus.STOPPED);
            assertThat(item.stoppedBy()).isEqualTo(ProductStoppedBy.ADMIN);
            assertThat(item.stoppedReason()).isEqualTo("성분 표기 오류");
        });
    }

    @Test
    @DisplayName("목록: 상태 탭으로 거르고, 탭별 개수는 상태 필터와 관계없이 센다")
    void getSellerProductsByStatus() {
        Product onSale = create(SELLER_ID, "판매 중", 10);
        Product soldOut = create(SELLER_ID, "품절", 1);
        ReflectionTestUtils.setField(option(soldOut), "stock", 0);
        Product stopped = create(SELLER_ID, "중지", 10);
        stop(stopped, "사유");

        assertThat(ids(getList(ProductDisplayStatus.ON_SALE, null))).containsExactly(onSale.getId());
        assertThat(ids(getList(ProductDisplayStatus.SOLD_OUT, null))).containsExactly(soldOut.getId());
        assertThat(ids(getList(ProductDisplayStatus.STOPPED, null))).containsExactly(stopped.getId());

        SellerProductListDto soldOutTab = getList(ProductDisplayStatus.SOLD_OUT, null);
        assertThat(soldOutTab.products().items().getFirst().status()).isEqualTo(ProductDisplayStatus.SOLD_OUT);
        assertThat(soldOutTab.counts()).isEqualTo(new SellerProductListDto.Counts(3, 1, 1, 1));
    }

    @Test
    @DisplayName("목록: 상품명으로 찾고, 탭별 개수에도 검색어를 적용한다")
    void getSellerProductsByKeyword() {
        Product cream = create(SELLER_ID, "수분 크림", 10);
        create(SELLER_ID, "립 틴트", 10);

        SellerProductListDto result = getList(null, " 크림 ");

        assertThat(ids(result)).containsExactly(cream.getId());
        assertThat(result.counts().all()).isEqualTo(1);
    }

    @Test
    @DisplayName("수정 폼: 대분류·소분류 ID와 이미지 자리를 채워 준다. 판매 중지된 상품도 조회할 수 있다")
    void getSellerProductForm() {
        Product product = create(SELLER_ID, "수분 크림", 10);
        stop(product, "사유");

        SellerProductFormDto form = productFacade.getSellerProductForm(MEMBER_ID, product.getId());

        assertThat(form).isEqualTo(new SellerProductFormDto(
                product.getId(), "수분 크림", 25_000, "설명", skincare.getParent().getId(), skincare.getId(), null, List.of()));
    }

    @Test
    @DisplayName("수정 폼: 다른 판매자의 상품이나 삭제된 상품은 PRODUCT_NOT_FOUND")
    void getSellerProductFormNotFound() {
        Product other = create(OTHER_SELLER_ID, "다른 판매자 크림", 10);
        Product deleted = create(SELLER_ID, "삭제된 크림", 10);
        setStatus(deleted, ProductSaleStatus.DELETED);

        for (Long productId : List.of(other.getId(), deleted.getId(), Long.MAX_VALUE)) {
            assertThatThrownBy(() -> productFacade.getSellerProductForm(MEMBER_ID, productId))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("수정: 보낸 항목만 바뀌고 재고는 그대로다")
    void modifyProduct() {
        Product product = create(SELLER_ID, "수분 크림", 10);

        productFacade.modifyProduct(MEMBER_ID, product.getId(), "진정 크림", 30_000L, makeup.getId(), null);

        assertThat(product.getName()).isEqualTo("진정 크림");
        assertThat(product.getPrice()).isEqualTo(30_000);
        assertThat(product.getCategory().getId()).isEqualTo(makeup.getId());
        assertThat(product.getDescription()).isEqualTo("설명");
        assertThat(option(product).getStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("수정: 다른 판매자의 상품은 PRODUCT_NOT_FOUND, 판매 중지 상품은 PRODUCT_NOT_EDITABLE")
    void modifyNotAllowed() {
        Product other = create(OTHER_SELLER_ID, "다른 판매자 크림", 10);
        Product stopped = create(SELLER_ID, "중지된 크림", 10);
        stop(stopped, "사유");

        assertThatThrownBy(() -> productFacade.modifyProduct(MEMBER_ID, other.getId(), "새 이름", null, null, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThatThrownBy(() -> productFacade.modifyProduct(MEMBER_ID, stopped.getId(), "새 이름", null, null, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
    }

    @Test
    @DisplayName("수정: 없는 카테고리는 CATEGORY_NOT_FOUND, 대분류는 CATEGORY_NOT_SELECTABLE")
    void modifyWithInvalidCategory() {
        Product product = create(SELLER_ID, "수분 크림", 10);
        Long beautyId = skincare.getParent().getId();

        assertThatThrownBy(() -> productFacade.modifyProduct(MEMBER_ID, product.getId(), null, null, Long.MAX_VALUE, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-CATEGORY_NOT_FOUND");
        assertThatThrownBy(() -> productFacade.modifyProduct(MEMBER_ID, product.getId(), null, null, beautyId, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_SELECTABLE");
    }

    private SellerProductListDto getList(ProductDisplayStatus status, String keyword) {
        return productFacade.getSellerProducts(MEMBER_ID, status, keyword, PageRequest.of(0, 20));
    }

    private List<Long> ids(SellerProductListDto result) {
        return result.products().items().stream().map(SellerProductSummaryDto::productId).toList();
    }

    private Product create(Long sellerId, String name, int stock) {
        Product product = productRepository.save(Product.create(sellerId, skincare, name, "설명", 25_000));
        productOptionRepository.save(ProductOption.createDefault(product, stock));
        return product;
    }

    private ProductOption option(Product product) {
        return productOptionRepository.findAllByProductIdInAndDeletedAtIsNullOrderByIdAsc(List.of(product.getId())).getFirst();
    }

    /** 판매 중지 기능 전이라 필드를 직접 바꾼다 */
    private void stop(Product product, String reason) {
        setStatus(product, ProductSaleStatus.STOPPED);
        ReflectionTestUtils.setField(product, "stoppedBy", ProductStoppedBy.ADMIN);
        ReflectionTestUtils.setField(product, "stoppedReason", reason);
    }

    private void setStatus(Product product, ProductSaleStatus status) {
        ReflectionTestUtils.setField(product, "status", status);
    }
}
