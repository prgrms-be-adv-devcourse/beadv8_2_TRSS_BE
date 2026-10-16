package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.domain.StockHistory;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 판매자 정보는 회원 컨텍스트 대신 MemberApi를 mock으로 바꿔서 준비한다.
 * 카테고리는 ProductDataInit의 초기 데이터를 쓴다. 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeProductTest {

    private static final long MEMBER_ID = 100L;
    private static final long SELLER_ID = 200L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @MockitoBean
    private MemberApi memberApi;

    @Test
    @DisplayName("등록하면 상품, 기본 옵션, INIT 재고 이력이 함께 저장된다")
    void createProduct() {
        givenApprovedSeller();

        Long productId = productFacade.createProduct(MEMBER_ID, skincareId(), "수분 크림", "촉촉한 크림", 25_000, 30, THUMBNAIL, List.of());

        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);

        List<ProductOption> options = productOptionRepository.findAll().stream()
                .filter(o -> o.getProduct().getId().equals(productId))
                .toList();
        assertThat(options).hasSize(1);
        ProductOption option = options.getFirst();
        assertThat(option.getName()).isEqualTo("기본");
        assertThat(option.getStock()).isEqualTo(30);

        List<StockHistory> histories = stockHistoryRepository.findAll().stream()
                .filter(h -> h.getOption().getId().equals(option.getId()))
                .toList();
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.getChangeType()).isEqualTo(StockChangeType.INIT);
            assertThat(history.getStockAfter()).isEqualTo(30);
        });
    }

    @Test
    @DisplayName("승인된 판매자가 아니면 회원 컨텍스트의 SELLER_NOT_APPROVED가 그대로 나가고, 상품은 저장되지 않는다")
    void createByNotApprovedSeller() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willThrow(new DomainException(MemberStubErrorCode.SELLER_NOT_APPROVED));
        Long skincareId = skincareId();
        long productCount = productRepository.count();

        assertThatThrownBy(() -> productFacade.createProduct(MEMBER_ID, skincareId, "수분 크림", "설명", 25_000, 30, THUMBNAIL, List.of()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("403-SELLER_NOT_APPROVED");
        assertThat(productRepository.count()).isEqualTo(productCount);
    }

    @Test
    @DisplayName("대분류나 사용 중지된 소분류로 등록하면 CATEGORY_NOT_SELECTABLE")
    void createWithNotSelectableCategory() {
        givenApprovedSeller();
        Long beautyId = beauty().categoryId();
        Long skincareId = skincareId();

        assertThatThrownBy(() -> productFacade.createProduct(MEMBER_ID, beautyId, "수분 크림", "설명", 25_000, 30, THUMBNAIL, List.of()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_SELECTABLE");

        productFacade.modifyCategory(skincareId, null, false);

        assertThatThrownBy(() -> productFacade.createProduct(MEMBER_ID, skincareId, "수분 크림", "설명", 25_000, 30, THUMBNAIL, List.of()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_SELECTABLE");
    }

    @Test
    @DisplayName("없는 카테고리로 등록하면 CATEGORY_NOT_FOUND")
    void createWithUnknownCategory() {
        givenApprovedSeller();

        assertThatThrownBy(() -> productFacade.createProduct(MEMBER_ID, Long.MAX_VALUE, "수분 크림", "설명", 25_000, 30, THUMBNAIL, List.of()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-CATEGORY_NOT_FOUND");
    }

    private void givenApprovedSeller() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
    }

    private CategoryTreeDto beauty() {
        return productFacade.getCategoryTree().stream()
                .filter(root -> root.name().equals("뷰티"))
                .findFirst().orElseThrow();
    }

    private Long skincareId() {
        return beauty().children().stream()
                .filter(child -> child.name().equals("스킨케어"))
                .findFirst().orElseThrow()
                .categoryId();
    }

    /** 회원 컨텍스트가 던지는 오류를 흉내 낸다. 다른 컨텍스트의 ErrorCode는 import하지 않는다. */
    @Getter
    @RequiredArgsConstructor
    @Accessors(fluent = true)
    private enum MemberStubErrorCode implements ErrorCode {
        SELLER_NOT_APPROVED(403, "승인된 판매자만 이용할 수 있습니다.");

        private final int status;
        private final String msg;
    }
}
