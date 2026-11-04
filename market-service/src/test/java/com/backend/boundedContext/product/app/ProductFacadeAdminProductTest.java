package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminProductStopRequestDto;
import com.backend.boundedContext.product.app.dto.AdminProductSummaryDto;
import com.backend.boundedContext.product.app.dto.ProductStopRequestDto;
import com.backend.boundedContext.product.domain.AdminProductDisplayStatus;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.domain.ProductStoppedBy;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.ProductStopRequestRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.out.OrderApi;
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
 * 관리자 상품 목록, 즉시 판매 중지, 판매 중지 요청 목록·승인·반려를 Facade로 검증한다.
 * 결제 대기 주문 취소는 주문 컨텍스트의 일이라 OrderApi를 mock으로 두고 호출만 확인한다.
 * 다른 데이터와 섞이지 않도록 이 테스트만 쓰는 판매자 ID로 상품을 만들고, 테스트마다 롤백된다.
 * mock 조합을 ProductFacadeSellerFinishTest와 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다.
 */
@SpringBootTest
@Transactional
class ProductFacadeAdminProductTest {

    private static final long MEMBER_ID = 970L;
    private static final long SELLER_ID = 9_700L;
    private static final long OTHER_SELLER_ID = 9_701L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private ProductStopRequestRepository productStopRequestRepository;

    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private Category skincare;
    private Category makeup;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
        makeup = categoryRepository.findByParentNameAndName("뷰티", "메이크업").orElseThrow();
    }

    // 상품 목록

    @Test
    @DisplayName("목록: 판매자의 상품을 삭제된 것까지 최신순으로 주고, 품절·중지 정보가 함께 나온다")
    void getProducts() {
        Product onSale = create(SELLER_ID, skincare, "수분 크림", 10);
        Product soldOut = create(SELLER_ID, skincare, "수분 토너", 0);
        Product stopped = create(SELLER_ID, makeup, "립스틱", 5);
        productFacade.stopProductByAdmin(stopped.getId(), "허위 광고");
        Product deleted = create(SELLER_ID, skincare, "선크림", 5);
        productFacade.deleteProduct(MEMBER_ID, deleted.getId());
        create(OTHER_SELLER_ID, skincare, "다른 판매자 크림", 10);

        PageResponse<AdminProductSummaryDto> result = list(SELLER_ID, null, null, null);

        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.items())
                .extracting(AdminProductSummaryDto::productId, AdminProductSummaryDto::status,
                        AdminProductSummaryDto::stock, AdminProductSummaryDto::stoppedBy, AdminProductSummaryDto::stoppedReason)
                .containsExactly(
                        tuple(deleted.getId(), AdminProductDisplayStatus.DELETED, 5, null, null),
                        tuple(stopped.getId(), AdminProductDisplayStatus.STOPPED, 5, ProductStoppedBy.ADMIN, "허위 광고"),
                        tuple(soldOut.getId(), AdminProductDisplayStatus.SOLD_OUT, 0, null, null),
                        tuple(onSale.getId(), AdminProductDisplayStatus.ON_SALE, 10, null, null));
        assertThat(result.items().getFirst().sellerId()).isEqualTo(SELLER_ID);
    }

    @Test
    @DisplayName("목록: 상태·카테고리(대분류면 아래 소분류까지)·상품명으로 거르고, 없는 카테고리는 CATEGORY_NOT_FOUND")
    void getProductsWithFilters() {
        Product cream = create(SELLER_ID, skincare, "수분 크림", 10);
        Product lipstick = create(SELLER_ID, makeup, "립스틱", 5);
        productFacade.stopProductByAdmin(lipstick.getId(), "허위 광고");

        assertThat(ids(list(SELLER_ID, AdminProductDisplayStatus.STOPPED, null, null))).containsExactly(lipstick.getId());
        assertThat(ids(list(SELLER_ID, null, skincare.getId(), null))).containsExactly(cream.getId());
        assertThat(ids(list(SELLER_ID, null, skincare.getParent().getId(), null))).containsExactly(lipstick.getId(), cream.getId());
        assertThat(ids(list(SELLER_ID, null, null, " 크림 "))).containsExactly(cream.getId());
        assertThatThrownBy(() -> list(SELLER_ID, null, Long.MAX_VALUE, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-CATEGORY_NOT_FOUND");
    }

    @Test
    @DisplayName("목록: 상태 필터는 화면 상태 기준이다 (판매중=재고 있음, 품절=판매 중 재고 0, 판매 중지, 삭제됨)")
    void getProductsByDisplayStatus() {
        Product onSale = create(SELLER_ID, skincare, "수분 크림", 10);
        Product soldOut = create(SELLER_ID, skincare, "수분 토너", 0);
        Product stopped = create(SELLER_ID, skincare, "수분 세럼", 5);
        productFacade.stopProductByAdmin(stopped.getId(), "허위 광고");
        Product deleted = create(SELLER_ID, skincare, "수분 젤", 5);
        productFacade.deleteProduct(MEMBER_ID, deleted.getId());

        assertThat(ids(list(SELLER_ID, AdminProductDisplayStatus.ON_SALE, null, null))).containsExactly(onSale.getId());
        assertThat(ids(list(SELLER_ID, AdminProductDisplayStatus.SOLD_OUT, null, null))).containsExactly(soldOut.getId());
        assertThat(ids(list(SELLER_ID, AdminProductDisplayStatus.STOPPED, null, null))).containsExactly(stopped.getId());
        assertThat(ids(list(SELLER_ID, AdminProductDisplayStatus.DELETED, null, null))).containsExactly(deleted.getId());
    }

    // 즉시 판매 중지

    @Test
    @DisplayName("즉시 중지: STOPPED(ADMIN)가 되어 구매자 상세에서 사라지고, 심사 중인 요청은 닫히며 결제 대기 주문 취소를 요청한다")
    void stopProductByAdmin() {
        Product product = create(SELLER_ID, skincare, "수분 크림", 10);
        ProductStopRequestDto request = productFacade.requestStop(MEMBER_ID, product.getId(), "단종");

        productFacade.stopProductByAdmin(product.getId(), "허위 광고");

        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
        assertThat(product.getStoppedBy()).isEqualTo(ProductStoppedBy.ADMIN);
        assertThat(product.getStoppedReason()).isEqualTo("허위 광고");
        assertThat(productStopRequestRepository.findById(request.requestId()).orElseThrow().getStatus())
                .isEqualTo(ProductStopRequestStatus.CANCELED);
        assertThatThrownBy(() -> productFacade.getProductDetail(product.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        verify(orderApi).cancelPendingOrders(List.of(product.getId()), OrderCancelType.PRODUCT_STOPPED);
    }

    @Test
    @DisplayName("즉시 중지: 이미 중지·삭제된 상품은 PRODUCT_NOT_ON_SALE, 없는 상품은 PRODUCT_NOT_FOUND이고 주문 취소는 요청하지 않는다")
    void stopProductByAdminNotAllowed() {
        Product stopped = create(SELLER_ID, skincare, "수분 크림", 10);
        stopped.stopBySellerWithdrawal("철회");
        Product deleted = create(SELLER_ID, skincare, "수분 크림", 10);
        deleted.delete();
        productRepository.flush();

        for (Product product : List.of(stopped, deleted)) {
            assertThatThrownBy(() -> productFacade.stopProductByAdmin(product.getId(), "허위 광고"))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_ON_SALE");
        }
        assertThatThrownBy(() -> productFacade.stopProductByAdmin(Long.MAX_VALUE, "허위 광고"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThat(stopped.getStoppedBy()).isEqualTo(ProductStoppedBy.SELLER_WITHDRAWN);
        verify(orderApi, never()).cancelPendingOrders(anyCollection(), any());
    }

    // 판매 중지 요청 심사

    @Test
    @DisplayName("요청 목록: 이 상태의 요청을 오래된 순으로 주고, 상품명과 판매자 ID가 함께 나온다")
    void getStopRequests() {
        Product first = create(SELLER_ID, skincare, "수분 크림", 10);
        Product second = create(SELLER_ID, skincare, "수분 토너", 10);
        Long firstRequestId = productFacade.requestStop(MEMBER_ID, first.getId(), "단종").requestId();
        Long secondRequestId = productFacade.requestStop(MEMBER_ID, second.getId(), "리뉴얼").requestId();
        productFacade.rejectStopRequest(secondRequestId, "반려");

        List<AdminProductStopRequestDto> pending = mine(ProductStopRequestStatus.PENDING);
        List<AdminProductStopRequestDto> rejected = mine(ProductStopRequestStatus.REJECTED);

        assertThat(pending)
                .extracting(AdminProductStopRequestDto::requestId, AdminProductStopRequestDto::productName,
                        AdminProductStopRequestDto::sellerId, AdminProductStopRequestDto::reason)
                .containsExactly(tuple(firstRequestId, "수분 크림", SELLER_ID, "단종"));
        assertThat(rejected).extracting(AdminProductStopRequestDto::requestId, AdminProductStopRequestDto::rejectReason)
                .containsExactly(tuple(secondRequestId, "반려"));
    }

    @Test
    @DisplayName("승인: 요청은 APPROVED, 상품은 판매자 사유로 STOPPED(SELLER_REQUEST)가 되고 결제 대기 주문 취소를 요청한다")
    void approveStopRequest() {
        Product product = create(SELLER_ID, skincare, "수분 크림", 10);
        Long requestId = productFacade.requestStop(MEMBER_ID, product.getId(), "원료 수급 중단").requestId();

        AdminProductStopRequestDto result = productFacade.approveStopRequest(requestId);

        assertThat(result.status()).isEqualTo(ProductStopRequestStatus.APPROVED);
        assertThat(result.reviewedAt()).isNotNull();
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
        assertThat(product.getStoppedBy()).isEqualTo(ProductStoppedBy.SELLER_REQUEST);
        assertThat(product.getStoppedReason()).isEqualTo("원료 수급 중단");
        verify(orderApi).cancelPendingOrders(List.of(product.getId()), OrderCancelType.PRODUCT_STOPPED);
    }

    @Test
    @DisplayName("반려: 요청은 REJECTED와 반려 사유가 남고, 상품은 판매 중이며 다시 요청할 수 있다")
    void rejectStopRequest() {
        Product product = create(SELLER_ID, skincare, "수분 크림", 10);
        Long requestId = productFacade.requestStop(MEMBER_ID, product.getId(), "단종").requestId();

        AdminProductStopRequestDto result = productFacade.rejectStopRequest(requestId, "  재고 소진 후 다시 요청해 주세요 ");

        assertThat(result.status()).isEqualTo(ProductStopRequestStatus.REJECTED);
        assertThat(result.rejectReason()).isEqualTo("재고 소진 후 다시 요청해 주세요");
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
        assertThat(productFacade.requestStop(MEMBER_ID, product.getId(), "단종").status())
                .isEqualTo(ProductStopRequestStatus.PENDING);
        verify(orderApi, never()).cancelPendingOrders(anyCollection(), any());
    }

    @Test
    @DisplayName("심사: 이미 처리되거나 상품 삭제로 닫힌 요청은 STOP_REQUEST_NOT_PENDING, 없는 요청은 STOP_REQUEST_NOT_FOUND")
    void reviewNotAllowed() {
        Product approvedProduct = create(SELLER_ID, skincare, "수분 크림", 10);
        Long approvedId = productFacade.requestStop(MEMBER_ID, approvedProduct.getId(), "단종").requestId();
        productFacade.approveStopRequest(approvedId);
        Product deletedProduct = create(SELLER_ID, skincare, "수분 토너", 10);
        Long canceledId = productFacade.requestStop(MEMBER_ID, deletedProduct.getId(), "단종").requestId();
        productFacade.deleteProduct(MEMBER_ID, deletedProduct.getId());

        for (Long requestId : List.of(approvedId, canceledId)) {
            assertThatThrownBy(() -> productFacade.approveStopRequest(requestId))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("409-STOP_REQUEST_NOT_PENDING");
            assertThatThrownBy(() -> productFacade.rejectStopRequest(requestId, "반려"))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("409-STOP_REQUEST_NOT_PENDING");
        }
        assertThatThrownBy(() -> productFacade.approveStopRequest(Long.MAX_VALUE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-STOP_REQUEST_NOT_FOUND");
    }

    private Product create(Long sellerId, Category category, String name, int stock) {
        Product product = productRepository.save(Product.create(sellerId, category, name, "설명", 25_000));
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product, Math.max(stock, 1)));
        ReflectionTestUtils.setField(option, "stock", stock);
        productRepository.flush();   // 잠금 조회가 DB 값을 다시 읽으므로 먼저 반영한다
        return product;
    }

    private PageResponse<AdminProductSummaryDto> list(Long sellerId, AdminProductDisplayStatus status, Long categoryId, String keyword) {
        return productFacade.getProductsForAdmin(sellerId, status, categoryId, keyword, PageRequest.of(0, 20));
    }

    private static List<Long> ids(PageResponse<AdminProductSummaryDto> result) {
        return result.items().stream().map(AdminProductSummaryDto::productId).toList();
    }

    /** 다른 데이터의 요청은 빼고 이 테스트 판매자의 요청만 본다 */
    private List<AdminProductStopRequestDto> mine(ProductStopRequestStatus status) {
        return productFacade.getStopRequestsForAdmin(status, PageRequest.of(0, 100)).items().stream()
                .filter(request -> request.sellerId().equals(SELLER_ID))
                .toList();
    }
}
