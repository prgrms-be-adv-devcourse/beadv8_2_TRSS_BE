package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.domain.StockHistory;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내부 API(조회·차감·복원)를 Facade로 검증한다. 상품은 저장소로 직접 만들고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeStockTest {

    private static final long ORDER_ID = 900_001L;
    /** 실제 주문 항목 ID와 겹치면 '이미 처리한 항목'으로 건너뛰므로, 쓰이지 않을 큰 ID를 쓴다 */
    private static final long ORDER_ITEM_ID = 9_100_001L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    private Product product;
    private ProductOption option;

    @BeforeEach
    void setUp() {
        product = productRepository.save(Product.create(
                10L, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "수분 크림", "설명", 25_000));
        option = productOptionRepository.save(ProductOption.createDefault(product, 5));
    }

    @Test
    @DisplayName("getOptions: 옵션·상품 정보와 판매 가능 여부를 돌려주고, 없는 ID는 빠진다")
    void getOptions() {
        List<OptionSnapshotDto> snapshots = productFacade.getOptions(List.of(option.getId(), Long.MAX_VALUE));

        assertThat(snapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.optionId()).isEqualTo(option.getId());
            assertThat(snapshot.productId()).isEqualTo(product.getId());
            assertThat(snapshot.sellerId()).isEqualTo(10L);
            assertThat(snapshot.productName()).isEqualTo("수분 크림");
            assertThat(snapshot.optionName()).isEqualTo("기본");
            assertThat(snapshot.price()).isEqualTo(25_000);
            assertThat(snapshot.stock()).isEqualTo(5);
            assertThat(snapshot.saleable()).isTrue();
            assertThat(snapshot.unsaleableReason()).isNull();
        });
    }

    @Test
    @DisplayName("getOptions: 삭제된 상품도 결과에 포함하고 판매 불가로 표시한다")
    void getOptionsDeletedProduct() {
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.DELETED);

        assertThat(productFacade.getOptions(List.of(option.getId()))).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.saleable()).isFalse();
            assertThat(snapshot.unsaleableReason()).isEqualTo(ProductUnsaleableReason.PRODUCT_DELETED);
        });
    }

    @Test
    @DisplayName("deductStock: 재고를 줄이고 ORDER_DEDUCT 이력을 남긴다. 같은 주문 항목으로 다시 부르면 아무것도 안 한다")
    void deductStock() {
        productFacade.deductStock(List.of(item(ORDER_ITEM_ID, 2)));
        productFacade.deductStock(List.of(item(ORDER_ITEM_ID, 2)));   // 중복 호출

        assertThat(option.getStock()).isEqualTo(3);
        assertThat(histories(StockChangeType.ORDER_DEDUCT)).singleElement().satisfies(history -> {
            assertThat(history.getChangeQty()).isEqualTo(-2);
            assertThat(history.getStockAfter()).isEqualTo(3);
            assertThat(history.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(history.getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        });
    }

    @Test
    @DisplayName("deductStock: 재고가 모자라면 OUT_OF_STOCK")
    void deductStockOutOfStock() {
        assertThatThrownBy(() -> productFacade.deductStock(List.of(item(ORDER_ITEM_ID, 6))))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-OUT_OF_STOCK");
    }

    @Test
    @DisplayName("deductStock: 없는 옵션이면 PRODUCT_NOT_FOUND")
    void deductStockUnknownOption() {
        assertThatThrownBy(() -> productFacade.deductStock(List.of(new StockChangeDto(ORDER_ID, ORDER_ITEM_ID, Long.MAX_VALUE, 1))))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("restoreStock: 재고를 되돌리고 이력을 남긴다. 같은 주문 항목·사유로 다시 부르면 아무것도 안 한다")
    void restoreStock() {
        productFacade.deductStock(List.of(item(ORDER_ITEM_ID, 2)));

        productFacade.restoreStock(List.of(item(ORDER_ITEM_ID, 2)), StockRestoreReason.ORDER_RESTORE);
        productFacade.restoreStock(List.of(item(ORDER_ITEM_ID, 2)), StockRestoreReason.ORDER_RESTORE);   // 중복 호출

        assertThat(option.getStock()).isEqualTo(5);
        assertThat(histories(StockChangeType.ORDER_RESTORE)).singleElement().satisfies(history -> {
            assertThat(history.getChangeQty()).isEqualTo(2);
            assertThat(history.getStockAfter()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("restoreStock: 차감한 적 없는 주문 항목이면 IllegalStateException")
    void restoreStockNotDeducted() {
        assertThatThrownBy(() -> productFacade.restoreStock(List.of(item(ORDER_ITEM_ID, 2)), StockRestoreReason.ORDER_RESTORE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("restoreStock: 삭제된 상품의 반품 복원은 PRODUCT_DELETED")
    void returnRestoreDeletedProduct() {
        productFacade.deductStock(List.of(item(ORDER_ITEM_ID, 2)));
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.DELETED);
        productRepository.flush();

        assertThatThrownBy(() -> productFacade.restoreStock(List.of(item(ORDER_ITEM_ID, 2)), StockRestoreReason.RETURN_RESTORE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_DELETED");
    }

    private StockChangeDto item(Long orderItemId, int quantity) {
        return new StockChangeDto(ORDER_ID, orderItemId, option.getId(), quantity);
    }

    private List<StockHistory> histories(StockChangeType changeType) {
        return stockHistoryRepository.findAll().stream()
                .filter(h -> h.getOption().getId().equals(option.getId()) && h.getChangeType() == changeType)
                .toList();
    }
}
