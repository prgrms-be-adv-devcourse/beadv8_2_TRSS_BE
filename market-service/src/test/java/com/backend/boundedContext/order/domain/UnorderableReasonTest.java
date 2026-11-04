package com.backend.boundedContext.order.domain;

import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 구매 불가 사유 판단 테스트. 의존성이 없어 Mock 없이 테스트한다.
 */
class UnorderableReasonTest {

    private OptionSnapshotDto saleable(int stock) {
        return new OptionSnapshotDto(100L, 10L, 2L, "립스틱", "기본", 15000, stock, true, null, null);
    }

    private OptionSnapshotDto unsaleable(ProductUnsaleableReason reason) {
        return new OptionSnapshotDto(100L, 10L, 2L, "립스틱", "기본", 15000, 5, false, reason, null);
    }

    @Test
    @DisplayName("모든 조건을 통과하면 null(주문 가능)")
    void orderable() {
        assertThat(UnorderableReason.resolve(saleable(5), 2, false)).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 옵션(null)이면 PRODUCT_DELETED")
    void notFound() {
        assertThat(UnorderableReason.resolve(null, 1, false)).isEqualTo(UnorderableReason.PRODUCT_DELETED);
    }

    @Test
    @DisplayName("상품 모듈의 판매 불가 사유를 그대로 옮긴다")
    void unsaleableReason() {
        assertThat(UnorderableReason.resolve(unsaleable(ProductUnsaleableReason.PRODUCT_DELETED), 1, false))
                .isEqualTo(UnorderableReason.PRODUCT_DELETED);
        assertThat(UnorderableReason.resolve(unsaleable(ProductUnsaleableReason.OPTION_DELETED), 1, false))
                .isEqualTo(UnorderableReason.OPTION_DELETED);
        assertThat(UnorderableReason.resolve(unsaleable(ProductUnsaleableReason.PRODUCT_STOPPED), 1, false))
                .isEqualTo(UnorderableReason.PRODUCT_STOPPED);
        assertThat(UnorderableReason.resolve(unsaleable(ProductUnsaleableReason.CATEGORY_INACTIVE), 1, false))
                .isEqualTo(UnorderableReason.CATEGORY_INACTIVE);
    }

    @Test
    @DisplayName("판매 불가인데 사유가 비어 있으면 계약 위반이라 IllegalStateException")
    void unsaleableWithoutReason() {
        assertThatThrownBy(() -> UnorderableReason.resolve(unsaleable(null), 1, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("옵션 재고가 0이면 SOLD_OUT")
    void soldOut() {
        assertThat(UnorderableReason.resolve(saleable(0), 1, false))
                .isEqualTo(UnorderableReason.SOLD_OUT);
    }

    @Test
    @DisplayName("재고가 담은 수량보다 적으면 STOCK_INSUFFICIENT")
    void stockInsufficient() {
        assertThat(UnorderableReason.resolve(saleable(1), 2, false))
                .isEqualTo(UnorderableReason.STOCK_INSUFFICIENT);
    }

    @Test
    @DisplayName("결제 대기 주문에 있으면 IN_PENDING_ORDER")
    void pending() {
        assertThat(UnorderableReason.resolve(saleable(5), 1, true)).isEqualTo(UnorderableReason.IN_PENDING_ORDER);
    }

    @Test
    @DisplayName("여러 사유에 해당하면 상품·옵션 쪽 사유가 우선")
    void priority() {
        // 판매 중지 + 결제 대기 → PRODUCT_STOPPED
        assertThat(UnorderableReason.resolve(unsaleable(ProductUnsaleableReason.PRODUCT_STOPPED), 1, true))
                .isEqualTo(UnorderableReason.PRODUCT_STOPPED);
        // 품절 + 결제 대기 → SOLD_OUT
        assertThat(UnorderableReason.resolve(saleable(0), 1, true))
                .isEqualTo(UnorderableReason.SOLD_OUT);
    }
}
