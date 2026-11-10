package com.backend.boundedContext.product.app.wishlist;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.WishlistItemDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 찜 추가·해제·목록과 상품 상세의 찜 여부를 Facade로 검증한다.
 * 카테고리는 마이그레이션이 넣은 기본 카테고리를 쓰고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeWishlistTest {

    private static final long SELLER_ID = 300L;
    private static final long MEMBER_ID = 7_001L;
    private static final long OTHER_MEMBER_ID = 7_002L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OptionStockRepository optionStockRepository;

    @Autowired
    private WishlistRepository wishlistRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    private Category skincare;

    @BeforeEach
    void setUp() {
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(SELLER_ID, 1L, "팔레트 상점", SellerStatus.APPROVED)));
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
    }

    @Test
    @DisplayName("찜하면 상세의 wished가 true가 되고, 찜 추가 이벤트가 outbox에 한 건 쌓인다")
    void add() {
        Product product = create("찜 크림", 10);

        productFacade.addWishlist(MEMBER_ID, product.getId());

        assertThat(productFacade.getProductDetail(product.getId(), MEMBER_ID).wished()).isTrue();
        assertThat(productFacade.getProductDetail(product.getId(), OTHER_MEMBER_ID).wished()).isFalse();
        assertThat(productFacade.getProductDetail(product.getId()).wished()).isFalse();   // 로그인하지 않음
        assertThat(events("WishAddedEvent", product.getId())).singleElement()
                .satisfies(event -> assertThat(event.getAggregateType()).isEqualTo("PRODUCT"));
    }

    @Test
    @DisplayName("같은 상품을 다시 찜해도 한 건만 남고, 이벤트도 처음 한 번만 발행한다")
    void addTwice() {
        Product product = create("찜 크림", 10);

        productFacade.addWishlist(MEMBER_ID, product.getId());
        productFacade.addWishlist(MEMBER_ID, product.getId());

        assertThat(wishlistRepository.findAll()).filteredOn(w -> w.getProduct().getId().equals(product.getId()))
                .hasSize(1);
        assertThat(events("WishAddedEvent", product.getId())).hasSize(1);
    }

    @Test
    @DisplayName("품절 상품은 찜할 수 있고, 판매 중지·없는 상품은 404 PRODUCT_NOT_FOUND")
    void addUnavailable() {
        Product soldOut = create("품절 크림", 0);
        Product stopped = create("중지 크림", 10);
        stopped.stopByAdmin("사유");

        productFacade.addWishlist(MEMBER_ID, soldOut.getId());

        assertThat(productFacade.getProductDetail(soldOut.getId(), MEMBER_ID).wished()).isTrue();
        assertThatThrownBy(() -> productFacade.addWishlist(MEMBER_ID, stopped.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThatThrownBy(() -> productFacade.addWishlist(MEMBER_ID, Long.MAX_VALUE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-PRODUCT_NOT_FOUND");
        assertThat(events("WishAddedEvent", stopped.getId())).isEmpty();
    }

    @Test
    @DisplayName("판매자는 본인 상품을 찜할 수 없다 (400 SELF_WISH_NOT_ALLOWED). 찜도 이벤트도 남지 않는다")
    void addOwnProduct() {
        Product product = create("내 크림", 10);
        given(memberApi.findSellerByMemberId(MEMBER_ID))
                .willReturn(Optional.of(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED)));

        assertThatThrownBy(() -> productFacade.addWishlist(MEMBER_ID, product.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-SELF_WISH_NOT_ALLOWED");
        assertThat(productFacade.getProductDetail(product.getId(), MEMBER_ID).wished()).isFalse();
        assertThat(events("WishAddedEvent", product.getId())).isEmpty();
    }

    @Test
    @DisplayName("판매자도 다른 판매자의 상품은 찜할 수 있다")
    void addOtherSellersProduct() {
        Product product = create("남의 크림", 10);
        given(memberApi.findSellerByMemberId(MEMBER_ID))
                .willReturn(Optional.of(new SellerDto(SELLER_ID + 1, MEMBER_ID, "다른 상점", SellerStatus.APPROVED)));

        productFacade.addWishlist(MEMBER_ID, product.getId());

        assertThat(productFacade.getProductDetail(product.getId(), MEMBER_ID).wished()).isTrue();
    }

    @Test
    @DisplayName("찜을 해제하면 해제 이벤트를 발행하고, 찜하지 않은 상품을 해제하면 아무 일도 없이 성공한다")
    void remove() {
        Product product = create("찜 크림", 10);
        Product notWished = create("다른 크림", 10);
        productFacade.addWishlist(MEMBER_ID, product.getId());

        productFacade.removeWishlist(MEMBER_ID, product.getId());
        productFacade.removeWishlist(MEMBER_ID, product.getId());
        productFacade.removeWishlist(MEMBER_ID, notWished.getId());

        assertThat(productFacade.getProductDetail(product.getId(), MEMBER_ID).wished()).isFalse();
        assertThat(events("WishRemovedEvent", product.getId())).hasSize(1);
        assertThat(events("WishRemovedEvent", notWished.getId())).isEmpty();
    }

    @Test
    @DisplayName("판매 중지된 상품의 찜도 해제할 수 있다")
    void removeStopped() {
        Product product = create("찜 크림", 10);
        productFacade.addWishlist(MEMBER_ID, product.getId());
        product.stopByAdmin("사유");

        productFacade.removeWishlist(MEMBER_ID, product.getId());

        assertThat(productFacade.getWishlist(MEMBER_ID, PageRequest.of(0, 20)).items()).isEmpty();
    }

    @Test
    @DisplayName("찜 목록은 최근에 찜한 순이고, 판매 중지된 상품은 available = false로 남는다. 다른 회원의 찜은 섞이지 않는다")
    void getWishlist() {
        Product first = create("먼저 찜한 크림", 10);
        Product soldOut = create("품절 크림", 0);
        Product stopped = create("중지될 크림", 10);
        productFacade.addWishlist(MEMBER_ID, first.getId());
        productFacade.addWishlist(MEMBER_ID, soldOut.getId());
        productFacade.addWishlist(MEMBER_ID, stopped.getId());
        productFacade.addWishlist(OTHER_MEMBER_ID, first.getId());
        stopped.stopByAdmin("사유");

        PageResponse<WishlistItemDto> result = productFacade.getWishlist(MEMBER_ID, PageRequest.of(0, 20));

        assertThat(result.items()).extracting(WishlistItemDto::productId)
                .containsExactly(stopped.getId(), soldOut.getId(), first.getId());
        assertThat(result.items()).extracting(WishlistItemDto::available).containsExactly(false, true, true);
        assertThat(result.items()).extracting(WishlistItemDto::soldOut).containsExactly(false, true, false);
        assertThat(result.items().getLast()).satisfies(item -> {
            assertThat(item.name()).isEqualTo("먼저 찜한 크림");
            assertThat(item.price()).isEqualTo(10_000);
            assertThat(item.wishedAt()).isNotNull();
        });
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(productFacade.getWishlist(OTHER_MEMBER_ID, PageRequest.of(0, 20)).items())
                .extracting(WishlistItemDto::productId).containsExactly(first.getId());
    }

    /** stock이 0이면 품절 상품 (초기 재고는 1 이상이어야 하므로 만든 뒤 0으로 바꾼다) */
    private Product create(String name, int stock) {
        Product product = productRepository.save(Product.create(SELLER_ID, skincare, name, "설명", 10_000));
        ProductOption option = saveOption(product, Math.max(stock, 1));
        if (stock == 0) ReflectionTestUtils.setField(optionStock(option), "stock", 0);
        return product;
    }

    private List<OutboxEvent> events(String eventType, Long productId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getEventType().equals(eventType) && e.getAggregateId().equals(productId))
                .toList();
    }

    /** 기본 옵션과 옵션 재고를 함께 저장한다 */
    private ProductOption saveOption(Product product, int stock) {
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        optionStockRepository.save(OptionStock.init(option, stock));
        return option;
    }

    /** 옵션의 재고 행 */
    private OptionStock optionStock(ProductOption option) {
        return optionStockRepository.findById(option.getId()).orElseThrow();
    }
}
