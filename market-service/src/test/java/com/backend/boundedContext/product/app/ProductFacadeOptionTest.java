package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.app.dto.SellerOptionDto;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

/**
 * 다중 옵션(최대 3단 조합)과 옵션별 추가금: 등록, 옵션 추가·추가금 수정·삭제, 상세·주문 스냅샷.
 * 판매자는 MemberApi mock으로 준비하고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeOptionTest {

    private static final long MEMBER_ID = 101L;
    private static final long SELLER_ID = 201L;
    private static final long PRICE = 20_000;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OptionStockRepository optionStockRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
        given(memberApi.getSellers(org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED)));
    }

    @Test
    @DisplayName("등록: 옵션 이름과 조합마다 값·추가금·초기 재고·INIT 이력이 저장되고, 상세에 옵션 이름과 단가가 나온다")
    void createWithOptions() {
        Long productId = create(List.of("색상", "사이즈"),
                spec(0, 10, "흰색", "M"), spec(2_000, 5, "흰색", "L"), spec(0, 3, "검정", "M"));

        ProductDetailDto detail = productFacade.getProductDetail(productId);

        assertThat(detail.price()).isEqualTo(PRICE);
        assertThat(detail.optionNames()).containsExactly("색상", "사이즈");
        assertThat(detail.options())
                .extracting(o -> o.name(), o -> o.values(), o -> o.additionalPrice(), o -> o.price(), o -> o.remainingStock())
                .containsExactly(
                        tuple("흰색 / M", List.of("흰색", "M"), 0L, 20_000L, null),
                        tuple("흰색 / L", List.of("흰색", "L"), 2_000L, 22_000L, 5),
                        tuple("검정 / M", List.of("검정", "M"), 0L, 20_000L, 3));
        assertThat(detail.options()).allSatisfy(option -> assertThat(stockHistories(option.optionId(), StockChangeType.INIT)).isEqualTo(1));
    }

    @Test
    @DisplayName("등록: 같은 조합이 두 번 있으면 OPTION_DUPLICATED, 값 개수가 옵션 이름과 다르면 OPTION_VALUE_INVALID")
    void createInvalidCombinations() {
        assertCode(() -> create(List.of("색상"), spec(0, 1, "흰색"), spec(1_000, 1, " 흰색 ")), "409-OPTION_DUPLICATED");
        assertCode(() -> create(List.of("색상", "사이즈"), spec(0, 1, "흰색")), "400-OPTION_VALUE_INVALID");
        assertCode(() -> create(List.of("색상"), new ProductOptionSpec[0]), "400-OPTION_VALUE_INVALID");
    }

    @Test
    @DisplayName("등록: 조합이 100개를 넘으면 OPTION_LIMIT_EXCEEDED")
    void createTooManyCombinations() {
        ProductOptionSpec[] specs = IntStream.rangeClosed(1, ProductOption.MAX_OPTIONS_PER_PRODUCT + 1)
                .mapToObj(i -> spec(0, 1, "값" + i))
                .toArray(ProductOptionSpec[]::new);

        assertCode(() -> create(List.of("번호"), specs), "400-OPTION_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("주문 스냅샷: 옵션 이름은 표시 이름, 가격은 단가(상품 가격 + 추가금)다")
    void optionSnapshot() {
        Long productId = create(List.of("색상", "사이즈"), spec(2_000, 5, "흰색", "L"));
        Long optionId = options(productId).getFirst().getId();

        OptionSnapshotDto snapshot = productFacade.getOptions(List.of(optionId)).getFirst();

        assertThat(snapshot.optionName()).isEqualTo("흰색 / L");
        assertThat(snapshot.price()).isEqualTo(22_000);
    }

    @Test
    @DisplayName("옵션 추가: 새 조합과 초기 재고·INIT 이력이 생기고, 같은 조합이면 OPTION_DUPLICATED")
    void addOption() {
        Long productId = create(List.of("색상"), spec(0, 5, "흰색"));

        Long optionId = productFacade.addOption(MEMBER_ID, productId, spec(1_000, 7, "검정"));

        assertThat(optionStockRepository.findById(optionId)).get().extracting(OptionStock::getStock).isEqualTo(7);
        assertThat(stockHistories(optionId, StockChangeType.INIT)).isEqualTo(1);
        assertThat(productFacade.getSellerProductForm(MEMBER_ID, productId).options())
                .extracting(SellerOptionDto::name, SellerOptionDto::price, SellerOptionDto::stock)
                .containsExactly(tuple("흰색", 20_000L, 5), tuple("검정", 21_000L, 7));

        assertCode(() -> productFacade.addOption(MEMBER_ID, productId, spec(0, 1, "검정")), "409-OPTION_DUPLICATED");
        assertCode(() -> productFacade.addOption(MEMBER_ID, productId, spec(0, 1, "검정", "M")), "400-OPTION_VALUE_INVALID");
    }

    @Test
    @DisplayName("옵션 추가: 삭제한 조합과 같은 값은 다시 추가할 수 있다")
    void addDeletedCombinationAgain() {
        Long productId = create(List.of("색상"), spec(0, 5, "흰색"), spec(0, 5, "검정"));
        Long black = options(productId).get(1).getId();
        productFacade.deleteOption(MEMBER_ID, productId, black);
        productOptionRepository.flush();

        Long again = productFacade.addOption(MEMBER_ID, productId, spec(500, 2, "검정"));
        productOptionRepository.flush();   // 유니크 인덱스는 삭제되지 않은 옵션만 본다

        assertThat(options(productId)).extracting(ProductOption::getId).containsExactly(options(productId).getFirst().getId(), again);
    }

    @Test
    @DisplayName("추가금 수정: 단가에 반영되고 재고는 그대로다. 다른 판매자 상품이면 PRODUCT_NOT_FOUND")
    void modifyOption() {
        Long productId = create(List.of("색상"), spec(0, 5, "흰색"));
        Long optionId = options(productId).getFirst().getId();

        productFacade.modifyOption(MEMBER_ID, productId, optionId, 3_000);

        assertThat(productFacade.getOptions(List.of(optionId)).getFirst())
                .extracting(OptionSnapshotDto::price, OptionSnapshotDto::stock)
                .containsExactly(23_000L, 5);
        assertCode(() -> productFacade.modifyOption(MEMBER_ID, productId, Long.MAX_VALUE, 0), "404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("옵션 삭제: 지운 옵션은 상세에서 빠지고 판매 불가(OPTION_DELETED)가 된다. 마지막 옵션은 지울 수 없다")
    void deleteOption() {
        Long productId = create(List.of("색상"), spec(0, 5, "흰색"), spec(0, 5, "검정"));
        Long white = options(productId).get(0).getId();
        Long black = options(productId).get(1).getId();

        productFacade.deleteOption(MEMBER_ID, productId, black);

        assertThat(productFacade.getProductDetail(productId).options()).extracting(o -> o.optionId()).containsExactly(white);
        assertThat(productFacade.getOptions(List.of(black)).getFirst().unsaleableReason())
                .isEqualTo(ProductUnsaleableReason.OPTION_DELETED);
        assertCode(() -> productFacade.deleteOption(MEMBER_ID, productId, black), "409-OPTION_DELETED");
        assertCode(() -> productFacade.deleteOption(MEMBER_ID, productId, white), "409-LAST_OPTION_NOT_DELETABLE");
    }

    @Test
    @DisplayName("옵션 없는 상품 등록(재고만)은 그대로 기본 옵션 1개를 만든다")
    void createWithoutOptions() {
        Long productId = productFacade.createProduct(MEMBER_ID, skincareId(), "크림", "설명", PRICE, 9, THUMBNAIL, List.of());

        ProductDetailDto detail = productFacade.getProductDetail(productId);
        assertThat(detail.optionNames()).isEmpty();
        assertThat(detail.options()).singleElement().satisfies(option -> {
            assertThat(option.name()).isEqualTo("기본");
            assertThat(option.values()).isEmpty();
            assertThat(option.price()).isEqualTo(PRICE);
        });
    }

    private Long create(List<String> optionNames, ProductOptionSpec... specs) {
        return productFacade.createProduct(MEMBER_ID, skincareId(), "티셔츠", "설명", PRICE,
                optionNames, List.of(specs), THUMBNAIL, List.of());
    }

    private static ProductOptionSpec spec(long additionalPrice, int stock, String... values) {
        return new ProductOptionSpec(List.of(values), additionalPrice, stock);
    }

    private List<ProductOption> options(Long productId) {
        return productOptionRepository.findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(productId);
    }

    private long stockHistories(Long optionId, StockChangeType changeType) {
        return stockHistoryRepository.findAll().stream()
                .filter(h -> h.getOption().getId().equals(optionId) && h.getChangeType() == changeType)
                .count();
    }

    private Long skincareId() {
        return categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow().getId();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String resultCode) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(resultCode);
    }
}
