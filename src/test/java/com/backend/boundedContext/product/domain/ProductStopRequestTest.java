package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
