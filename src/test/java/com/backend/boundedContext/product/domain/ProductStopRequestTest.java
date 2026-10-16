package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductStopRequestTest {

    private final Category skincare = Category.createChild(Category.createRoot("뷰티", 0), "스킨케어", 0);

    @Test
    @DisplayName("판매 중인 상품에 요청하면 심사 중(PENDING)이고, 판매자 ID와 앞뒤 공백을 지운 사유가 남는다")
    void create() {
        Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);

        ProductStopRequest request = ProductStopRequest.create(product, "  원료 수급 중단  ");

        assertThat(request.getStatus()).isEqualTo(ProductStopRequestStatus.PENDING);
        assertThat(request.getSellerId()).isEqualTo(10L);
        assertThat(request.getReason()).isEqualTo("원료 수급 중단");
        assertThat(request.getReviewedAt()).isNull();
    }

    @Test
    @DisplayName("판매 중지·삭제된 상품에는 요청할 수 없다 (PRODUCT_NOT_EDITABLE)")
    void createForNotEditableProduct() {
        Product stopped = Product.create(10L, skincare, "수분 크림", "설명", 25_000);
        stopped.stopBySellerWithdrawal("철회");

        assertThatThrownBy(() -> ProductStopRequest.create(stopped, "사유"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
    }

    @Test
    @DisplayName("심사 중인 요청을 닫으면 CANCELED와 처리 시각이 남고, 이미 닫힌 요청은 다시 닫을 수 없다")
    void cancel() {
        ProductStopRequest request = ProductStopRequest.create(Product.create(10L, skincare, "수분 크림", "설명", 25_000), "사유");

        request.cancel();

        assertThat(request.getStatus()).isEqualTo(ProductStopRequestStatus.CANCELED);
        assertThat(request.getReviewedAt()).isNotNull();
        assertThatThrownBy(request::cancel)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-STOP_REQUEST_NOT_PENDING");
    }

    @Test
    @DisplayName("승인하면 APPROVED와 처리 시각이 남고, 상품은 판매자가 적은 사유로 STOPPED(SELLER_REQUEST)가 된다")
    void approve() {
        Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);
        ProductStopRequest request = ProductStopRequest.create(product, "원료 수급 중단");

        request.approve();

        assertThat(request.getStatus()).isEqualTo(ProductStopRequestStatus.APPROVED);
        assertThat(request.getReviewedAt()).isNotNull();
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
        assertThat(product.getStoppedBy()).isEqualTo(ProductStoppedBy.SELLER_REQUEST);
        assertThat(product.getStoppedReason()).isEqualTo("원료 수급 중단");
    }

    @Test
    @DisplayName("반려하면 REJECTED와 앞뒤 공백을 지운 반려 사유가 남고, 상품은 판매 중이다")
    void reject() {
        Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);
        ProductStopRequest request = ProductStopRequest.create(product, "사유");

        request.reject("  재고 소진 후 다시 요청해 주세요 ");

        assertThat(request.getStatus()).isEqualTo(ProductStopRequestStatus.REJECTED);
        assertThat(request.getRejectReason()).isEqualTo("재고 소진 후 다시 요청해 주세요");
        assertThat(request.getReviewedAt()).isNotNull();
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
    }

    @Test
    @DisplayName("이미 처리된 요청은 다시 승인·반려할 수 없다 (STOP_REQUEST_NOT_PENDING)")
    void reviewNotPending() {
        ProductStopRequest approved = ProductStopRequest.create(Product.create(10L, skincare, "수분 크림", "설명", 25_000), "사유");
        approved.approve();
        ProductStopRequest rejected = ProductStopRequest.create(Product.create(10L, skincare, "수분 크림", "설명", 25_000), "사유");
        rejected.reject("반려");

        for (ProductStopRequest request : List.of(approved, rejected)) {
            assertThatThrownBy(request::approve)
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("409-STOP_REQUEST_NOT_PENDING");
            assertThatThrownBy(() -> request.reject("반려"))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("409-STOP_REQUEST_NOT_PENDING");
        }
        assertThat(rejected.getRejectReason()).isEqualTo("반려");
    }
}
