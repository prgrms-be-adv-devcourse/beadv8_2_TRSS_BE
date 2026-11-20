package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.MemberRecommendationsDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.app.dto.RecommendationSectionDto;
import com.backend.boundedContext.product.app.dto.RecommendedProductDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.RecommendBasis;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRecommendStore;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.recommend.ProductRecommendRedisStore;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.CartOptionDto;
import com.backend.shared.order.dto.PurchasedProductDto;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 개인화 추천 (테스트는 engine=jpa라 DB 후보: 선호 소분류·대분류의 최근 상품, 텍스트·행동 점수 없음).
 * 기준 상품(구매·찜·장바구니·별점)을 고르고, 뺄 상품을 빼고, 이유·섹션·중복 제거·저장이 맞는지 본다.
 * 텍스트 점수 합치기는 점수 단위 테스트와 ES 테스트가 본다. 저장소는 로컬 Redis(테스트 1번 DB)를 쓰고 끝나면 지운다.
 */
@SpringBootTest
@Transactional
class ProductRecommendationsTest {

    private static final long SELLER_ID = 300L;
    /** 시드 상품과 같은 가격이 되지 않게 흔하지 않은 가격을 쓴다 */
    private static final long PRICE = 23_459;

    @Autowired private ProductFacade productFacade;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductOptionRepository productOptionRepository;
    @Autowired private OptionStockRepository optionStockRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private WishlistRepository wishlistRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private ProductRecommendStore productRecommendStore;
    @Autowired private StringRedisTemplate redisTemplate;

    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private final long memberId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final List<Long> memberIds = new ArrayList<>();
    private Category skincare;
    private Category makeup;
    private Category top;

    @BeforeEach
    void setUp() {
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(SELLER_ID, 1L, "팔레트 상점", SellerStatus.APPROVED)));
        given(orderApi.getRecentPurchases(any(), any(), anyInt())).willReturn(List.of());
        given(orderApi.getCartOptions(any())).willReturn(List.of());
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
        makeup = categoryRepository.findByParentNameAndName("뷰티", "메이크업").orElseThrow();
        top = categoryRepository.findByParentNameAndName("의류", "상의").orElseThrow();
        memberIds.add(memberId);
    }

    @AfterEach
    void tearDown() {
        memberIds.forEach(id -> redisTemplate.delete(ProductRecommendRedisStore.KEY_PREFIX + id));
    }

    @Test
    @DisplayName("회원 추천: 구매·찜·장바구니·높은 별점을 기준으로, 이미 산·찜한·담은 상품과 낮게 평가한 상품·품절·다른 대분류는 빼고 이유와 함께 준다. "
            + "섹션은 최근 구매·최근 찜마다 나오고 같은 상품은 한 번만 나온다. 결과를 저장한다")
    void memberRecommendations() {
        Product purchased = create(skincare, "구매한 크림", PRICE, 10);
        Product wished = create(skincare, "찜한 토너", PRICE, 10);
        Product lowRated = create(skincare, "별로였던 크림", PRICE, 10);
        Product inCart = create(makeup, "담아 둔 립", PRICE, 10);
        Product best = create(skincare, "추천될 크림", PRICE, 10);
        Product soldOut = create(skincare, "품절 크림", PRICE, 0);
        Product otherParent = create(top, "다른 대분류 셔츠", PRICE, 10);
        // 섹션을 채울 후보. 로컬 시드 상품이 없는 DB(CI)에서도 forYou에 쓰이고 남는 후보가 있어야 섹션이 나온다
        for (int i = 1; i <= 4; i++) {
            create(skincare, "섹션 후보 크림 " + i, PRICE * 2, 10);
        }
        LocalDateTime now = LocalDateTime.now();
        given(orderApi.getRecentPurchases(eq(memberId), any(), anyInt()))
                .willReturn(List.of(new PurchasedProductDto(purchased.getId(), now.minusDays(1))));
        given(orderApi.getCartOptions(memberId))
                .willReturn(List.of(new CartOptionDto(option(inCart).getId(), now.minusDays(1))));
        wishlistRepository.insertIfAbsent(memberId, wished.getId(), now.minusDays(2));
        reviewRepository.save(Review.create(ThreadLocalRandom.current().nextLong(1L << 40), memberId, lowRated.getId(), 1L,
                "기본", "리뷰어", 1, "별로"));

        MemberRecommendationsDto result = productFacade.getMemberRecommendations(memberId, 2);

        assertThat(result.personalized()).isTrue();
        List<Long> forYou = result.forYou().stream().map(item -> item.product().productId()).toList();
        assertThat(forYou).first().isEqualTo(best.getId());   // 선호 소분류(스킨케어) + 같은 가격대
        RecommendedProductDto first = result.forYou().getFirst();
        assertThat(first.reason()).isNotNull();
        assertThat(first.reason().basis()).isEqualTo(RecommendBasis.PURCHASE);   // 같은 소분류에서 가장 무거운 기준 상품
        assertThat(first.reason().productId()).isEqualTo(purchased.getId());
        assertThat(first.reason().productName()).isEqualTo("구매한 크림");

        assertThat(result.sections()).extracting(RecommendationSectionDto::basis, RecommendationSectionDto::seedProductId)
                .startsWith(org.assertj.core.groups.Tuple.tuple(RecommendBasis.PURCHASE, purchased.getId()));
        List<Long> all = new ArrayList<>(forYou);
        result.sections().forEach(section -> all.addAll(section.items().stream().map(ProductSummaryDto::productId).toList()));
        assertThat(all).doesNotHaveDuplicates()
                .doesNotContain(purchased.getId(), wished.getId(), lowRated.getId(), inCart.getId(),
                        soldOut.getId(), otherParent.getId());

        assertThat(productRecommendStore.find(memberId)).hasValueSatisfying(stored -> {
            assertThat(stored.personalized()).isTrue();
            assertThat(stored.forYou()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("로그인하지 않았거나 행동 이력이 없으면 인기순, personalized=false, 섹션 없음 (이력 없음도 저장해 두고 다시 읽지 않는다)")
    void notPersonalized() {
        create(skincare, "인기 크림", PRICE, 10);

        MemberRecommendationsDto anonymous = productFacade.getMemberRecommendations(null, 5);
        MemberRecommendationsDto noHistory = productFacade.getMemberRecommendations(memberId, 5);

        assertThat(anonymous.personalized()).isFalse();
        assertThat(anonymous.sections()).isEmpty();
        assertThat(anonymous.forYou()).isNotEmpty().allSatisfy(item -> assertThat(item.reason()).isNull());
        assertThat(noHistory.personalized()).isFalse();
        assertThat(productRecommendStore.find(memberId)).hasValueSatisfying(stored -> assertThat(stored.personalized()).isFalse());
    }

    @Test
    @DisplayName("최근 본 상품 기반: 최근 본 상품과 비슷한 상품을 주고, 최근 본 상품·없는 상품 ID는 빠진다. 기준이 없으면 빈 목록")
    void recentRecommendations() {
        Product viewed = create(skincare, "최근 본 크림", PRICE, 10);
        Product alsoViewed = create(skincare, "같이 본 크림", PRICE, 10);
        Product best = create(skincare, "비슷한 크림", PRICE, 10);

        List<Long> ids = productFacade.getRecentRecommendations(List.of(viewed.getId(), Long.MAX_VALUE, alsoViewed.getId()), 5)
                .stream().map(ProductSummaryDto::productId).toList();

        assertThat(ids).first().isEqualTo(best.getId());
        assertThat(ids).doesNotContain(viewed.getId(), alsoViewed.getId());
        assertThat(productFacade.getRecentRecommendations(List.of(Long.MAX_VALUE), 5)).isEmpty();
        assertThat(productFacade.getRecentRecommendations(null, 5)).isEmpty();
    }

    private Product create(Category category, String name, long price, int stock) {
        Product product = productRepository.save(Product.create(SELLER_ID, category, name, "설명", price));
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        OptionStock optionStock = optionStockRepository.save(OptionStock.init(option, Math.max(stock, 1)));
        if (stock == 0) ReflectionTestUtils.setField(optionStock, "stock", 0);
        return product;
    }

    private ProductOption option(Product product) {
        return productOptionRepository.findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(product.getId()).getFirst();
    }
}
