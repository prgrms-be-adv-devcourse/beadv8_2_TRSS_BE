package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductStoppedBy;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 판매자 전체 상품 중지·재개를 회원 컨텍스트가 부르는 ProductApi로 검증한다.
 * 결제 대기 주문 취소는 주문 컨텍스트의 일이라 OrderApi를 mock으로 두고 호출만 확인한다.
 * 다른 데이터와 섞이지 않도록 이 테스트만 쓰는 판매자 ID로 상품을 만들고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeSellerStopTest {

    private static final long SELLER_ID = 9_800L;
    private static final long OTHER_SELLER_ID = 9_801L;

    @Autowired
    private ProductApi productApi;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private OrderApi orderApi;

    /** 쓰지 않지만 ProductFacadeSellerFinishTest와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    private Category skincare;

    @BeforeEach
    void setUp() {
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
    }

    @Test
    @DisplayName("중지: 판매자의 판매 중 상품만 SELLER_WITHDRAWN으로 중지하고, 그 상품들로 결제 대기 주문 취소를 요청한다")
    void stopAllBySeller() {
        Product first = create(SELLER_ID);
        Product second = create(SELLER_ID);
        Product stoppedByAdmin = create(SELLER_ID);
        stopByAdmin(stoppedByAdmin);
        Product deleted = create(SELLER_ID);
        ReflectionTestUtils.setField(deleted, "status", ProductSaleStatus.DELETED);
        Product other = create(OTHER_SELLER_ID);

        productApi.stopAllBySeller(SELLER_ID, "판매자 철회 요청");

        for (Product product : List.of(first, second)) {
            assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
            assertThat(product.getStoppedBy()).isEqualTo(ProductStoppedBy.SELLER_WITHDRAWN);
            assertThat(product.getStoppedReason()).isEqualTo("판매자 철회 요청");
            assertThat(product.getStoppedAt()).isNotNull();
        }
        assertThat(stoppedByAdmin.getStoppedBy()).isEqualTo(ProductStoppedBy.ADMIN);
        assertThat(stoppedByAdmin.getStoppedReason()).isEqualTo("관리자 중지");
        assertThat(deleted.getStatus()).isEqualTo(ProductSaleStatus.DELETED);
        assertThat(other.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);

        verify(orderApi).cancelPendingOrders(List.of(first.getId(), second.getId()), OrderCancelType.PRODUCT_STOPPED);
    }

    @Test
    @DisplayName("중지: 판매 중 상품이 없으면 아무것도 하지 않고, 다시 호출해도 결과가 같다")
    void stopAllBySellerTwice() {
        productApi.stopAllBySeller(SELLER_ID, "판매자 제재");   // 상품 없음
        verify(orderApi, never()).cancelPendingOrders(anyCollection(), any());

        Product product = create(SELLER_ID);
        productApi.stopAllBySeller(SELLER_ID, "판매자 철회 요청");
        productApi.stopAllBySeller(SELLER_ID, "판매자 제재");   // 철회 요청 중 제재: 이미 중지돼 있어 그대로 둔다

        assertThat(product.getStoppedReason()).isEqualTo("판매자 철회 요청");
        verify(orderApi).cancelPendingOrders(List.of(product.getId()), OrderCancelType.PRODUCT_STOPPED);
    }

    @Test
    @DisplayName("중지된 상품은 구매자 상세에서 404, 주문 쪽 옵션 조회에서는 판매 불가(PRODUCT_STOPPED)다")
    void stoppedProductIsNotSaleable() {
        Product product = create(SELLER_ID);
        ProductOption option = productOptionRepository.findAllByProductIdInAndDeletedAtIsNullOrderByIdAsc(List.of(product.getId())).getFirst();

        productApi.stopAllBySeller(SELLER_ID, "판매자 철회 요청");

        assertThatThrownBy(() -> productFacade.getProductDetail(product.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThat(productApi.getOptions(List.of(option.getId()))).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.saleable()).isFalse();
            assertThat(snapshot.unsaleableReason()).isEqualTo(ProductUnsaleableReason.PRODUCT_STOPPED);
        });
    }

    @Test
    @DisplayName("재개: 판매자 철회로 중지된 상품만 판매 중으로 되돌리고, 관리자가 중지한 상품과 다른 판매자 상품은 그대로다")
    void resumeAllBySeller() {
        Product product = create(SELLER_ID);
        Product stoppedByAdmin = create(SELLER_ID);
        stopByAdmin(stoppedByAdmin);
        Product other = create(OTHER_SELLER_ID);
        productApi.stopAllBySeller(SELLER_ID, "판매자 철회 요청");
        productApi.stopAllBySeller(OTHER_SELLER_ID, "다른 판매자 철회 요청");

        productApi.resumeAllBySeller(SELLER_ID);

        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
        assertThat(product.getStoppedBy()).isNull();
        assertThat(product.getStoppedReason()).isNull();
        assertThat(product.getStoppedAt()).isNull();
        assertThat(stoppedByAdmin.getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
        assertThat(stoppedByAdmin.getStoppedBy()).isEqualTo(ProductStoppedBy.ADMIN);
        assertThat(other.getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
        assertThat(productFacade.getProductDetail(product.getId()).productId()).isEqualTo(product.getId());   // 다시 구매자에게 보인다
    }

    @Test
    @DisplayName("재개: 되돌릴 상품이 없거나 다시 호출해도 오류 없이 결과가 같다")
    void resumeAllBySellerTwice() {
        Product product = create(SELLER_ID);

        productApi.resumeAllBySeller(SELLER_ID);   // 중지된 상품 없음
        productApi.stopAllBySeller(SELLER_ID, "판매자 철회 요청");
        productApi.resumeAllBySeller(SELLER_ID);
        productApi.resumeAllBySeller(SELLER_ID);

        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
    }

    private Product create(Long sellerId) {
        Product product = productRepository.save(Product.create(sellerId, skincare, "판매자 중지 테스트", "설명", 25_000));
        productOptionRepository.save(ProductOption.createDefault(product, 10));
        productRepository.flush();   // 잠금 조회가 DB 값을 다시 읽으므로 먼저 반영한다
        return product;
    }

    /** 관리자 중지 기능 전이라 필드를 직접 바꾼다 */
    private void stopByAdmin(Product product) {
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.STOPPED);
        ReflectionTestUtils.setField(product, "stoppedBy", ProductStoppedBy.ADMIN);
        ReflectionTestUtils.setField(product, "stoppedReason", "관리자 중지");
        productRepository.flush();
    }
}
