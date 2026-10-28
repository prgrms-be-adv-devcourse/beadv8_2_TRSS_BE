package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductStopRequestDto;
import com.backend.boundedContext.product.app.dto.SellerProductSummaryDto;
import com.backend.boundedContext.product.app.dto.StockHistoryDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductStopRequest;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.domain.StockHistory;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.ProductStopRequestRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.out.ProductApi;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 판매자 상품 삭제, 판매 중지 요청, 재고 이력 조회를 Facade로 검증한다.
 * 결제 대기 주문 취소는 주문 컨텍스트의 일이라 OrderApi를 mock으로 두고 호출만 확인한다.
 * 다른 데이터와 섞이지 않도록 이 테스트만 쓰는 판매자 ID로 상품을 만들고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeSellerFinishTest {

    private static final long MEMBER_ID = 900L;
    private static final long SELLER_ID = 9_900L;
    private static final long OTHER_SELLER_ID = 9_901L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductApi productApi;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private ProductStopRequestRepository productStopRequestRepository;

    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private Category skincare;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
    }

    // 삭제

    @Test
    @DisplayName("삭제: DELETED가 되어 판매자 목록·구매자 상세에서 사라지고, 결제 대기 주문 취소를 요청한다")
    void deleteProduct() {
        Product product = create(SELLER_ID, 10);

        productFacade.deleteProduct(MEMBER_ID, product.getId());

        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.DELETED);
        assertThat(product.getDeletedAt()).isNotNull();
        assertThat(sellerList()).extracting(SellerProductSummaryDto::productId).doesNotContain(product.getId());
        assertThatThrownBy(() -> productFacade.getProductDetail(product.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        verify(orderApi).cancelPendingOrders(List.of(product.getId()), OrderCancelType.PRODUCT_STOPPED);
    }

    @Test
    @DisplayName("삭제: 품절 상품도 지울 수 있고, 심사 중인 판매 중지 요청은 CANCELED로 닫힌다")
    void deleteSoldOutProductWithPendingRequest() {
        Product product = create(SELLER_ID, 1);
        ReflectionTestUtils.setField(option(product), "stock", 0);
        ProductStopRequestDto request = productFacade.requestStop(MEMBER_ID, product.getId(), "단종");

        productFacade.deleteProduct(MEMBER_ID, product.getId());

        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.DELETED);
        assertThat(stopRequest(request.requestId())).satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(ProductStopRequestStatus.CANCELED);
            assertThat(saved.getReviewedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("삭제: 판매 중지 상품은 PRODUCT_NOT_EDITABLE, 다른 판매자·이미 삭제된 상품은 PRODUCT_NOT_FOUND이고 주문 취소는 요청하지 않는다")
    void deleteNotAllowed() {
        Product stopped = create(SELLER_ID, 10);
        stopped.stopBySellerWithdrawal("철회");
        Product other = create(OTHER_SELLER_ID, 10);
        Product deleted = create(SELLER_ID, 10);
        productFacade.deleteProduct(MEMBER_ID, deleted.getId());

        assertThatThrownBy(() -> productFacade.deleteProduct(MEMBER_ID, stopped.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
        for (Long productId : List.of(other.getId(), deleted.getId(), Long.MAX_VALUE)) {
            assertThatThrownBy(() -> productFacade.deleteProduct(MEMBER_ID, productId))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        }
        verify(orderApi, never()).cancelPendingOrders(List.of(stopped.getId()), OrderCancelType.PRODUCT_STOPPED);
    }

    // 판매 중지 요청

    @Test
    @DisplayName("중지 요청: 심사 중으로 저장되고, 판매자 목록에 심사 중으로 보이며, 상품은 계속 판매 중이다")
    void requestStop() {
        Product product = create(SELLER_ID, 10);
        Product another = create(SELLER_ID, 10);

        ProductStopRequestDto request = productFacade.requestStop(MEMBER_ID, product.getId(), "  원료 수급 중단 ");

        assertThat(request.productId()).isEqualTo(product.getId());
        assertThat(request.status()).isEqualTo(ProductStopRequestStatus.PENDING);
        assertThat(request.reason()).isEqualTo("원료 수급 중단");
        assertThat(sellerList())
                .extracting(SellerProductSummaryDto::productId, SellerProductSummaryDto::stopRequestPending)
                .containsExactly(tuple(another.getId(), false), tuple(product.getId(), true));
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
        assertThat(productFacade.getProductDetail(product.getId()).productId()).isEqualTo(product.getId());
    }

    @Test
    @DisplayName("중지 요청: 심사 중인 요청이 있으면 STOP_REQUEST_PENDING, 판매 중지 상품은 PRODUCT_NOT_EDITABLE, 다른 판매자 상품은 PRODUCT_NOT_FOUND")
    void requestStopNotAllowed() {
        Product product = create(SELLER_ID, 10);
        productFacade.requestStop(MEMBER_ID, product.getId(), "단종");
        Product stopped = create(SELLER_ID, 10);
        stopped.stopBySellerWithdrawal("철회");
        Product other = create(OTHER_SELLER_ID, 10);

        assertThatThrownBy(() -> productFacade.requestStop(MEMBER_ID, product.getId(), "다시"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-STOP_REQUEST_PENDING");
        assertThatThrownBy(() -> productFacade.requestStop(MEMBER_ID, stopped.getId(), "단종"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
        assertThatThrownBy(() -> productFacade.requestStop(MEMBER_ID, other.getId(), "단종"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("중지 요청: 판매자 철회로 일괄 중지되면 심사 중인 요청이 닫히고, 철회가 거절돼 판매 중으로 돌아오면 다시 요청할 수 있다")
    void requestStopAfterSellerWithdrawal() {
        Product product = create(SELLER_ID, 10);
        ProductStopRequestDto first = productFacade.requestStop(MEMBER_ID, product.getId(), "단종");

        productApi.stopAllBySeller(SELLER_ID, "판매자 철회 요청");
        assertThat(stopRequest(first.requestId()).getStatus()).isEqualTo(ProductStopRequestStatus.CANCELED);

        productApi.resumeAllBySeller(SELLER_ID);
        ProductStopRequestDto second = productFacade.requestStop(MEMBER_ID, product.getId(), "단종");
        assertThat(second.status()).isEqualTo(ProductStopRequestStatus.PENDING);
    }

    // 재고 이력

    @Test
    @DisplayName("재고 이력: 옵션의 이력을 최신순으로 주고, 주문 이력에는 주문 ID가 있다")
    void getStockHistories() {
        Product product = create(SELLER_ID, 10);
        ProductOption option = option(product);
        stockHistoryRepository.save(StockHistory.ofInit(option));
        productFacade.adjustStock(MEMBER_ID, product.getId(), option.getId(), 5);
        ReflectionTestUtils.setField(option, "stock", 13);
        stockHistoryRepository.save(StockHistory.ofOrder(option, StockChangeType.ORDER_DEDUCT, -2, 77L, 9_200_001L));

        PageResponse<StockHistoryDto> result = productFacade.getStockHistories(
                MEMBER_ID, product.getId(), option.getId(), PageRequest.of(0, 20));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.items())
                .extracting(StockHistoryDto::changeType, StockHistoryDto::changeQty, StockHistoryDto::stockAfter, StockHistoryDto::orderId)
                .containsExactly(
                        tuple(StockChangeType.ORDER_DEDUCT, -2, 13, 77L),
                        tuple(StockChangeType.ADJUST, 5, 15, null),
                        tuple(StockChangeType.INIT, 10, 10, null));
    }

    @Test
    @DisplayName("재고 이력: 판매 중지 상품도 볼 수 있고, 다른 상품의 옵션·다른 판매자·삭제된 상품은 PRODUCT_NOT_FOUND")
    void getStockHistoriesAccess() {
        Product stopped = create(SELLER_ID, 10);
        stopped.stopBySellerWithdrawal("철회");
        Product another = create(SELLER_ID, 10);
        Product other = create(OTHER_SELLER_ID, 10);
        Product deleted = create(SELLER_ID, 10);
        productFacade.deleteProduct(MEMBER_ID, deleted.getId());

        assertThat(histories(stopped.getId(), option(stopped).getId()).items()).isEmpty();
        assertThatThrownBy(() -> histories(stopped.getId(), option(another).getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThatThrownBy(() -> histories(other.getId(), option(other).getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThatThrownBy(() -> histories(deleted.getId(), option(deleted).getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("상품이 없을 때 판매 종료 처리는 주문 취소를 요청하지 않는다")
    void noOrderCancelForNothing() {
        productApi.stopAllBySeller(SELLER_ID, "판매자 철회 요청");

        verify(orderApi, never()).cancelPendingOrders(anyCollection(), any());
    }

    private Product create(Long sellerId, int stock) {
        Product product = productRepository.save(Product.create(sellerId, skincare, "판매자 상품 테스트", "설명", 25_000));
        productOptionRepository.save(ProductOption.createDefault(product, stock));
        productRepository.flush();   // 잠금 조회가 DB 값을 다시 읽으므로 먼저 반영한다
        return product;
    }

    private ProductOption option(Product product) {
        return productOptionRepository.findAllByProductIdInAndDeletedAtIsNullOrderByIdAsc(List.of(product.getId())).getFirst();
    }

    private List<SellerProductSummaryDto> sellerList() {
        return productFacade.getSellerProducts(MEMBER_ID, null, null, PageRequest.of(0, 20)).items();
    }

    private ProductStopRequest stopRequest(Long requestId) {
        return productStopRequestRepository.findById(requestId).orElseThrow();
    }

    private PageResponse<StockHistoryDto> histories(Long productId, Long optionId) {
        return productFacade.getStockHistories(MEMBER_ID, productId, optionId, PageRequest.of(0, 20));
    }
}
