package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.OptionStockDto;
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
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

/**
 * 판매자 재고 조정을 Facade로 검증한다. 판매자는 MemberApi mock으로 준비하고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeStockAdjustTest {

    private static final long MEMBER_ID = 100L;
    private static final long SELLER_ID = 200L;
    private static final long OTHER_SELLER_ID = 201L;

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

    @MockitoBean
    private MemberApi memberApi;

    private Product product;
    private ProductOption option;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));

        product = createProduct(SELLER_ID);
        option = productOptionRepository.save(ProductOption.createDefault(product, 5));
    }

    @Test
    @DisplayName("증감량만큼 재고가 바뀌고, 조정 후 재고를 돌려주며, ADJUST 이력이 남는다")
    void adjustStock() {
        OptionStockDto increased = productFacade.adjustStock(MEMBER_ID, product.getId(), option.getId(), 10);
        OptionStockDto decreased = productFacade.adjustStock(MEMBER_ID, product.getId(), option.getId(), -3);

        assertThat(increased.stock()).isEqualTo(15);
        assertThat(decreased).isEqualTo(new OptionStockDto(option.getId(), 12));
        assertThat(histories(StockChangeType.ADJUST))
                .extracting(StockHistory::getChangeQty, StockHistory::getStockAfter)
                .containsExactly(tuple(10, 15), tuple(-3, 12));
    }

    @Test
    @DisplayName("결과가 0 미만이면 STOCK_NEGATIVE, 재고와 이력은 그대로다")
    void adjustStockNegative() {
        assertThatThrownBy(() -> productFacade.adjustStock(MEMBER_ID, product.getId(), option.getId(), -6))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-STOCK_NEGATIVE");

        assertThat(option.getStock()).isEqualTo(5);
        assertThat(histories(StockChangeType.ADJUST)).isEmpty();
    }

    @Test
    @DisplayName("다른 판매자의 상품이면 PRODUCT_NOT_FOUND")
    void adjustOtherSellersProduct() {
        Product otherProduct = createProduct(OTHER_SELLER_ID);
        ProductOption otherOption = productOptionRepository.save(ProductOption.createDefault(otherProduct, 5));

        assertThatThrownBy(() -> productFacade.adjustStock(MEMBER_ID, otherProduct.getId(), otherOption.getId(), 1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThat(otherOption.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("다른 상품의 옵션이면 PRODUCT_NOT_FOUND (같은 판매자여도)")
    void adjustOptionOfAnotherProduct() {
        Product anotherProduct = createProduct(SELLER_ID);
        ProductOption anotherOption = productOptionRepository.save(ProductOption.createDefault(anotherProduct, 5));

        assertThatThrownBy(() -> productFacade.adjustStock(MEMBER_ID, product.getId(), anotherOption.getId(), 1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("없는 옵션이면 PRODUCT_NOT_FOUND")
    void adjustUnknownOption() {
        assertThatThrownBy(() -> productFacade.adjustStock(MEMBER_ID, product.getId(), Long.MAX_VALUE, 1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("판매 중지된 상품이면 PRODUCT_NOT_EDITABLE")
    void adjustStoppedProduct() {
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.STOPPED);
        productRepository.flush();   // 잠근 뒤 DB에서 다시 읽으므로 먼저 반영한다

        assertThatThrownBy(() -> productFacade.adjustStock(MEMBER_ID, product.getId(), option.getId(), 1))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
    }

    private Product createProduct(Long sellerId) {
        return productRepository.save(Product.create(
                sellerId, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "수분 크림", "설명", 25_000));
    }

    private List<StockHistory> histories(StockChangeType changeType) {
        return stockHistoryRepository.findAll().stream()
                .filter(h -> h.getOption().getId().equals(option.getId()) && h.getChangeType() == changeType)
                .toList();
    }
}
