package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 옵션의 판매 가능 여부와 옵션 재고(OptionStock)의 재고 변경 규칙.
 * 판매 중지·삭제 기능은 아직 없으므로 상태는 ReflectionTestUtils로 바꿔서 준비한다.
 */
class ProductOptionTest {

    /** 옵션 재고는 저장된 옵션(ID 있음)에만 만들 수 있으므로 ID를 넣어 둔다 */
    private static ProductOption savedOption(Product product) {
        ProductOption option = ProductOption.createDefault(product);
        ReflectionTestUtils.setField(option, "id", 1L);
        return option;
    }

    private final Category skincare = Category.createChild(Category.createRoot("뷰티", 0), "스킨케어", 0);
    private final Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);
    private final ProductOption option = savedOption(product);
    private final OptionStock stock = OptionStock.init(option, 5);

    @Test
    @DisplayName("판매 불가 이유는 상품 삭제 > 옵션 삭제 > 상품 중지 > 카테고리 사용 중지 순으로 하나만 나온다")
    void unsaleableReasonPriority() {
        assertThat(option.isSaleable()).isTrue();
        assertThat(option.getUnsaleableReason()).isNull();

        skincare.modify(null, false);
        assertThat(option.getUnsaleableReason()).isEqualTo(ProductUnsaleableReason.CATEGORY_INACTIVE);

        stopProduct();
        assertThat(option.getUnsaleableReason()).isEqualTo(ProductUnsaleableReason.PRODUCT_STOPPED);

        deleteOption();
        assertThat(option.getUnsaleableReason()).isEqualTo(ProductUnsaleableReason.OPTION_DELETED);

        deleteProduct();
        assertThat(option.getUnsaleableReason()).isEqualTo(ProductUnsaleableReason.PRODUCT_DELETED);
    }

    @Test
    @DisplayName("차감하면 재고가 줄고, 남은 재고를 모두 차감할 수 있다")
    void deductStock() {
        stock.deductStock(2);
        assertThat(stock.getStock()).isEqualTo(3);

        stock.deductStock(3);
        assertThat(stock.getStock()).isZero();
    }

    @Test
    @DisplayName("재고보다 많이 차감하거나 재고가 0이면 OUT_OF_STOCK, 재고는 그대로다")
    void deductStockOverStock() {
        assertThatThrownBy(() -> stock.deductStock(6))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-OUT_OF_STOCK");
        assertThat(stock.getStock()).isEqualTo(5);

        stock.deductStock(5);
        assertThatThrownBy(() -> stock.deductStock(1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-OUT_OF_STOCK");
    }

    @Test
    @DisplayName("판매할 수 없으면 재고가 있어도 PRODUCT_NOT_ON_SALE")
    void deductStockNotSaleable() {
        stopProduct();

        assertThatThrownBy(() -> stock.deductStock(1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_ON_SALE");
        assertThat(stock.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("주문 취소 복원은 중지·삭제된 상품이어도 재고를 늘린다")
    void orderRestore() {
        stopProduct();
        stock.restoreStock(2, StockChangeType.ORDER_RESTORE);
        assertThat(stock.getStock()).isEqualTo(7);

        deleteProduct();
        stock.restoreStock(1, StockChangeType.ORDER_RESTORE);
        assertThat(stock.getStock()).isEqualTo(8);
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.DELETED);   // 상태는 바꾸지 않는다
    }

    @Test
    @DisplayName("복원 결과가 최대 재고를 넘으면 STOCK_LIMIT_EXCEEDED, 최대 재고까지는 늘릴 수 있다")
    void restoreStockLimitExceeded() {
        stock.restoreStock(OptionStock.MAX_STOCK - 5, StockChangeType.ORDER_RESTORE);
        assertThat(stock.getStock()).isEqualTo(OptionStock.MAX_STOCK);

        assertThatThrownBy(() -> stock.restoreStock(1, StockChangeType.ORDER_RESTORE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-STOCK_LIMIT_EXCEEDED");
        assertThatThrownBy(() -> stock.restoreStock(Integer.MAX_VALUE, StockChangeType.RETURN_RESTORE))   // int가 넘쳐 음수가 되지 않는다
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-STOCK_LIMIT_EXCEEDED");
        assertThat(stock.getStock()).isEqualTo(OptionStock.MAX_STOCK);
    }

    @Test
    @DisplayName("반품 복원은 삭제된 상품이면 PRODUCT_DELETED, 삭제된 옵션이면 OPTION_DELETED (상품을 먼저 본다)")
    void returnRestoreDeleted() {
        deleteOption();
        assertThatThrownBy(() -> stock.restoreStock(1, StockChangeType.RETURN_RESTORE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-OPTION_DELETED");

        deleteProduct();
        assertThatThrownBy(() -> stock.restoreStock(1, StockChangeType.RETURN_RESTORE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_DELETED");
        assertThat(stock.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("수량이 1보다 작으면 IllegalArgumentException")
    void invalidQuantity() {
        assertThatThrownBy(() -> stock.deductStock(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.restoreStock(-1, StockChangeType.ORDER_RESTORE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("재고 조정: 증감량만큼 늘리거나 줄이고, 0까지 줄일 수 있다")
    void adjustStock() {
        stock.adjustStock(10);
        assertThat(stock.getStock()).isEqualTo(15);

        stock.adjustStock(-15);
        assertThat(stock.getStock()).isZero();
    }

    @Test
    @DisplayName("재고 조정: 결과가 0 미만이면 STOCK_NEGATIVE, 재고는 그대로다")
    void adjustStockNegative() {
        assertThatThrownBy(() -> stock.adjustStock(-6))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-STOCK_NEGATIVE");
        assertThat(stock.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("재고 조정: 결과가 최대 재고를 넘으면 STOCK_LIMIT_EXCEEDED, 재고는 그대로다")
    void adjustStockLimitExceeded() {
        assertThatThrownBy(() -> stock.adjustStock(OptionStock.MAX_STOCK - 4))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-STOCK_LIMIT_EXCEEDED");
        assertThatThrownBy(() -> stock.adjustStock(Integer.MAX_VALUE))   // int가 넘쳐 음수로 판단되지 않는다
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-STOCK_LIMIT_EXCEEDED");
        assertThat(stock.getStock()).isEqualTo(5);

        stock.adjustStock(OptionStock.MAX_STOCK - 5);
        assertThat(stock.getStock()).isEqualTo(OptionStock.MAX_STOCK);
    }

    @Test
    @DisplayName("재고 조정: 판매 중지·삭제된 상품이면 PRODUCT_NOT_EDITABLE")
    void adjustStockNotEditableProduct() {
        stopProduct();
        assertThatThrownBy(() -> stock.adjustStock(1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");

        deleteProduct();
        assertThatThrownBy(() -> stock.adjustStock(1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
        assertThat(stock.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("재고 조정: 삭제된 옵션이면 OPTION_DELETED, 상품도 삭제됐으면 상품을 먼저 본다")
    void adjustStockDeletedOption() {
        deleteOption();
        assertThatThrownBy(() -> stock.adjustStock(1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-OPTION_DELETED");

        deleteProduct();
        assertThatThrownBy(() -> stock.adjustStock(1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
    }

    @Test
    @DisplayName("재고 조정: 판매 중이 아닌 카테고리여도 조정할 수 있다")
    void adjustStockInactiveCategory() {
        skincare.modify(null, false);

        stock.adjustStock(3);
        assertThat(stock.getStock()).isEqualTo(8);
    }

    @Test
    @DisplayName("재고 조정: 증감량이 0이면 IllegalArgumentException")
    void adjustStockZero() {
        assertThatThrownBy(() -> stock.adjustStock(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("남은 수량은 재고가 1 ~ 5개일 때만 주고, 0이면 품절이다")
    void lowStockRemaining() {
        assertThat(stock.getLowStockRemaining()).isEqualTo(5);
        assertThat(stock.isSoldOut()).isFalse();

        stock.adjustStock(1);
        assertThat(stock.getLowStockRemaining()).isNull();

        stock.adjustStock(-6);
        assertThat(stock.getLowStockRemaining()).isNull();
        assertThat(stock.isSoldOut()).isTrue();
    }

    @Test
    @DisplayName("구매자에게 보여 줄 수 있는 상품은 판매 중이고 카테고리가 사용 중인 상품뿐이다")
    void displayable() {
        assertThat(product.isDisplayable()).isTrue();

        skincare.modify(null, false);
        assertThat(product.isDisplayable()).isFalse();

        skincare.modify(null, true);
        stopProduct();
        assertThat(product.isDisplayable()).isFalse();

        deleteProduct();
        assertThat(product.isDisplayable()).isFalse();
    }

    private void stopProduct() {
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.STOPPED);
    }

    private void deleteProduct() {
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.DELETED);
    }

    private void deleteOption() {
        ReflectionTestUtils.setField(option, "deletedAt", LocalDateTime.now());
    }
}
