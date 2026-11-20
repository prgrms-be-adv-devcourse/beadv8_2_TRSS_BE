package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSimilarCandidate;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductJpaSimilarAdapter;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.ProductSimilarQuery;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 비슷한 상품 (테스트는 engine=jpa라 DB 후보: 같은 대분류의 최근 상품, 텍스트·행동 점수 0).
 * 후보 범위와 거르기, 같은 소분류·가격 근접도 순서를 본다. 텍스트·행동 점수는 ES 테스트와 점수 단위 테스트가 본다.
 */
@SpringBootTest
@Transactional
class ProductSimilarProductsTest {

    private static final long SELLER_ID = 300L;
    /** 시드 상품과 같은 가격이 되지 않게 흔하지 않은 가격을 쓴다 */
    private static final long PRICE = 23_457;

    @Autowired private ProductFacade productFacade;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductOptionRepository productOptionRepository;
    @Autowired private OptionStockRepository optionStockRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductDiscountRepository productDiscountRepository;
    @Autowired private ProductJpaSimilarAdapter jpaSimilarAdapter;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    private Category skincare;
    private Category makeup;
    private Category top;

    @BeforeEach
    void setUp() {
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(SELLER_ID, 1L, "팔레트 상점", SellerStatus.APPROVED)));
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
        makeup = categoryRepository.findByParentNameAndName("뷰티", "메이크업").orElseThrow();
        top = categoryRepository.findByParentNameAndName("의류", "상의").orElseThrow();
    }

    @Test
    @DisplayName("같은 대분류에서만 찾고, 같은 소분류·같은 가격대가 앞에 오며, 기준 상품·품절·판매 중지·다른 대분류 상품은 빠진다")
    void candidatesAndOrder() {
        Product base = create(skincare, "기준 크림", PRICE, 10);
        Product sameCategory = create(skincare, "같은 소분류 크림", PRICE, 10);
        Product soldOut = create(skincare, "품절 크림", PRICE, 0);
        Product stopped = create(skincare, "중지 크림", PRICE, 10);
        ReflectionTestUtils.setField(stopped, "status", ProductSaleStatus.STOPPED);
        Product otherParent = create(top, "다른 대분류 셔츠", PRICE, 10);

        List<Long> ids = productFacade.getSimilarProducts(base.getId(), 20).stream().map(ProductSummaryDto::productId).toList();

        assertThat(ids).first().isEqualTo(sameCategory.getId());   // 같은 소분류 + 같은 가격이 가장 높다
        assertThat(ids).doesNotContain(base.getId(), soldOut.getId(), stopped.getId(), otherParent.getId());
        assertThat(ids).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("DB 후보: 같은 소분류는 대분류의 더 최근 상품이 많아도 따로 찾아 들어가고, 같은 대분류 다른 소분류도 들어가며, "
            + "다른 대분류는 빠진다. 가격은 지금 기본가(할인가)다")
    void jpaCandidates() {
        Product base = create(skincare, "기준 크림", PRICE, 10);
        Product sameCategory = create(skincare, "같은 소분류 크림", 20_000, 10);
        Product lip1 = create(makeup, "같은 대분류 립 1", PRICE, 10);   // 같은 소분류보다 최근에 등록된 다른 소분류 상품들
        Product lip2 = create(makeup, "같은 대분류 립 2", PRICE, 10);
        Product otherParent = create(top, "다른 대분류 셔츠", PRICE, 10);
        LocalDateTime now = LocalDateTime.now();
        productDiscountRepository.save(ProductDiscount.create(sameCategory, 15_000, now.minusHours(1), now.plusDays(1),
                now.minusHours(2)));
        List<Long> parentCategoryIds = List.of(skincare.getId(), makeup.getId());

        Map<Long, ProductSimilarCandidate> candidates = jpaSimilarAdapter.findCandidates(
                        new ProductSimilarQuery(base.getId(), "기준 크림", skincare.getId(), parentCategoryIds, 2))
                .candidates().stream()
                .collect(Collectors.toMap(ProductSimilarCandidate::productId, Function.identity()));

        assertThat(candidates).containsKeys(sameCategory.getId(), lip1.getId(), lip2.getId())
                .doesNotContainKeys(base.getId(), otherParent.getId());
        assertThat(candidates.get(sameCategory.getId()).effectivePrice()).isEqualTo(15_000);   // 할인가
        assertThat(candidates.get(lip1.getId()).effectivePrice()).isEqualTo(PRICE);
    }

    @Test
    @DisplayName("size는 1~20으로 맞춘다")
    void sizeIsClamped() {
        Product base = create(skincare, "기준 크림", PRICE, 10);
        create(skincare, "같은 소분류 크림 1", PRICE, 10);
        create(skincare, "같은 소분류 크림 2", PRICE, 10);

        assertThat(productFacade.getSimilarProducts(base.getId(), 0)).hasSize(1);
        assertThat(productFacade.getSimilarProducts(base.getId(), 1000)).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("기준 상품이 판매 중이 아니면 404")
    void notDisplayableBase() {
        Product base = create(skincare, "기준 크림", PRICE, 10);
        ReflectionTestUtils.setField(base, "status", ProductSaleStatus.STOPPED);

        assertThatThrownBy(() -> productFacade.getSimilarProducts(base.getId(), 10))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
    }

    private Product create(Category category, String name, long price, int stock) {
        Product product = productRepository.save(Product.create(SELLER_ID, category, name, "설명", price));
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        if (stock > 0) {
            optionStockRepository.save(OptionStock.init(option, stock));
        } else {
            OptionStock empty = optionStockRepository.save(OptionStock.init(option, 1));
            ReflectionTestUtils.setField(empty, "stock", 0);
        }
        return product;
    }
}
