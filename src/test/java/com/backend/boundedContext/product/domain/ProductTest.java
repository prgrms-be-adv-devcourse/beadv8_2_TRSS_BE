package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    private final Category root = Category.createRoot("뷰티", 0);
    private final Category skincare = Category.createChild(root, "스킨케어", 0);

    @Test
    @DisplayName("상품을 만들면 판매 중이고, 이름과 설명의 앞뒤 공백은 지워진다")
    void create() {
        Product product = Product.create(10L, skincare, "  수분 크림 ", " 촉촉한 크림 ", 25_000);

        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
        assertThat(product.getSellerId()).isEqualTo(10L);
        assertThat(product.getCategory()).isSameAs(skincare);
        assertThat(product.getName()).isEqualTo("수분 크림");
        assertThat(product.getDescription()).isEqualTo("촉촉한 크림");
        assertThat(product.getPrice()).isEqualTo(25_000);
    }

    @Test
    @DisplayName("대분류나 사용 중지된 소분류로 만들면 CATEGORY_NOT_SELECTABLE")
    void createWithNotSelectableCategory() {
        assertThatThrownBy(() -> Product.create(10L, root, "수분 크림", "설명", 25_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_SELECTABLE");

        skincare.modify(null, false);

        assertThatThrownBy(() -> Product.create(10L, skincare, "수분 크림", "설명", 25_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_SELECTABLE");
    }

    @Test
    @DisplayName("기본 옵션은 이름이 '기본'이고 초기 재고를 갖는다")
    void createDefaultOption() {
        Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);

        ProductOption option = ProductOption.createDefault(product, 30);

        assertThat(option.getName()).isEqualTo(ProductOption.DEFAULT_NAME).isEqualTo("기본");
        assertThat(option.getStock()).isEqualTo(30);
        assertThat(option.getProduct()).isSameAs(product);
    }

    @Test
    @DisplayName("기본 옵션의 초기 재고는 1 이상이어야 한다")
    void createDefaultOptionWithZeroStock() {
        Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);

        assertThatThrownBy(() -> ProductOption.createDefault(product, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("수정은 null이 아닌 값만 반영하고, 이름과 설명의 앞뒤 공백은 지워진다")
    void modify() {
        Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);
        Category makeup = Category.createChild(root, "메이크업", 1);

        product.modify(" 진정 크림 ", null, makeup, null);

        assertThat(product.getName()).isEqualTo("진정 크림");
        assertThat(product.getPrice()).isEqualTo(25_000);
        assertThat(product.getCategory()).isSameAs(makeup);
        assertThat(product.getDescription()).isEqualTo("설명");

        product.modify(null, 30_000L, null, " 새 설명 ");

        assertThat(product.getName()).isEqualTo("진정 크림");
        assertThat(product.getPrice()).isEqualTo(30_000);
        assertThat(product.getCategory()).isSameAs(makeup);
        assertThat(product.getDescription()).isEqualTo("새 설명");
    }

    @Test
    @DisplayName("판매 중지·삭제된 상품은 수정할 수 없다 (PRODUCT_NOT_EDITABLE)")
    void modifyNotEditable() {
        for (ProductSaleStatus status : List.of(ProductSaleStatus.STOPPED, ProductSaleStatus.DELETED)) {
            Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);
            ReflectionTestUtils.setField(product, "status", status);

            assertThatThrownBy(() -> product.modify("새 이름", null, null, null))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
            assertThat(product.getName()).isEqualTo("수분 크림");
        }
    }

    @Test
    @DisplayName("대분류나 사용 중지된 소분류로 옮기면 CATEGORY_NOT_SELECTABLE, 다른 값도 바뀌지 않는다")
    void modifyWithNotSelectableCategory() {
        Product product = Product.create(10L, skincare, "수분 크림", "설명", 25_000);
        Category makeup = Category.createChild(root, "메이크업", 1);
        makeup.modify(null, false);

        for (Category category : List.of(root, makeup)) {
            assertThatThrownBy(() -> product.modify("새 이름", null, category, null))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_SELECTABLE");
        }
        assertThat(product.getName()).isEqualTo("수분 크림");
        assertThat(product.getCategory()).isSameAs(skincare);
    }

    @Test
    @DisplayName("INIT 재고 이력은 초기 재고만큼 늘고, 주문 정보가 없다")
    void initStockHistory() {
        ProductOption option = ProductOption.createDefault(Product.create(10L, skincare, "수분 크림", "설명", 25_000), 30);

        StockHistory history = StockHistory.ofInit(option);

        assertThat(history.getChangeType()).isEqualTo(StockChangeType.INIT);
        assertThat(history.getChangeQty()).isEqualTo(30);
        assertThat(history.getStockAfter()).isEqualTo(30);
        assertThat(history.getOrderId()).isNull();
        assertThat(history.getOrderItemId()).isNull();
    }
}
