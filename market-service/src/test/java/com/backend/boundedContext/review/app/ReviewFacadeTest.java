package com.backend.boundedContext.review.app;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.review.app.dto.MyReviewDto;
import com.backend.boundedContext.review.app.dto.ProductReviewDto;
import com.backend.boundedContext.review.app.dto.ReviewSummaryDto;
import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.domain.ReviewSort;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 리뷰 작성·수정·삭제·조회를 Facade로 검증한다.
 * 구매 확정 여부(OrderApi)와 작성자 이름(MemberApi)은 mock이고, 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ReviewFacadeTest {

    private static final long MEMBER_ID = 8_001L;
    private static final long OTHER_MEMBER_ID = 8_002L;
    private static final long PRODUCT_ID = 980_001L;   // 리뷰는 상품을 ID로만 참조하므로 없는 상품 ID로도 쓸 수 있다
    private static final long OPTION_ID = 990_001L;

    @Autowired
    private ReviewFacade reviewFacade;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OptionStockRepository optionStockRepository;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    @BeforeEach
    void setUp() {
        given(memberApi.getMember(anyLong())).willAnswer(invocation -> member(invocation.getArgument(0), "김유진"));
    }

    @Test
    @DisplayName("작성: 구매 확정된 본인 주문 항목이면 주문 항목의 상품·옵션과 가린 이름으로 저장하고, 작성 이벤트를 한 건 발행한다")
    void create() {
        confirmed(MEMBER_ID, 101L, PRODUCT_ID);

        Long reviewId = reviewFacade.createReview(MEMBER_ID, 101L, 5, "촉촉하고 향이 좋아요. 재구매 의사 있어요.");

        Review review = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(review.getOrderItemId()).isEqualTo(101L);
        assertThat(review.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(review.getOptionId()).isEqualTo(OPTION_ID);
        assertThat(review.getOptionName()).isEqualTo("50ml");
        assertThat(review.getReviewerName()).isEqualTo("김*진");
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(events("ReviewCreatedEvent", PRODUCT_ID)).singleElement()
                .satisfies(event -> assertThat(event.getAggregateType()).isEqualTo("REVIEW"));
    }

    @Test
    @DisplayName("작성: 구매 확정된 본인 주문 항목이 아니면 404 ORDER_ITEM_NOT_REVIEWABLE이고, 회원 조회·저장·이벤트가 없다")
    void createNotReviewable() {
        given(orderApi.findConfirmedOrderItem(MEMBER_ID, 102L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reviewFacade.createReview(MEMBER_ID, 102L, 5, "리뷰 내용은 열 글자 이상"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-ORDER_ITEM_NOT_REVIEWABLE");
        assertThat(reviewRepository.existsByOrderItemId(102L)).isFalse();
        assertThat(events("ReviewCreatedEvent", PRODUCT_ID)).isEmpty();
    }

    @Test
    @DisplayName("작성: 이미 리뷰를 쓴 주문 항목이면 409 REVIEW_ALREADY_EXISTS")
    void createTwice() {
        confirmed(MEMBER_ID, 103L, PRODUCT_ID);
        reviewFacade.createReview(MEMBER_ID, 103L, 5, "첫 번째 리뷰 내용입니다.");

        assertThatThrownBy(() -> reviewFacade.createReview(MEMBER_ID, 103L, 1, "두 번째 리뷰 내용입니다."))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-REVIEW_ALREADY_EXISTS");
        assertThat(events("ReviewCreatedEvent", PRODUCT_ID)).hasSize(1);
    }

    @Test
    @DisplayName("작성: 리뷰를 지운 주문 항목에도 다시 쓸 수 없다 (409 REVIEW_ALREADY_EXISTS)")
    void createAfterDelete() {
        confirmed(MEMBER_ID, 104L, PRODUCT_ID);
        Long reviewId = reviewFacade.createReview(MEMBER_ID, 104L, 1, "별로였어요. 지우고 다시 쓸래요.");
        reviewFacade.deleteReview(MEMBER_ID, reviewId);

        assertThatThrownBy(() -> reviewFacade.createReview(MEMBER_ID, 104L, 5, "다시 쓴 좋은 리뷰입니다."))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-REVIEW_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("작성: 탈퇴한 회원이면 401 UNAUTHORIZED")
    void createByDeletedMember() {
        confirmed(MEMBER_ID, 105L, PRODUCT_ID);
        given(memberApi.getMember(MEMBER_ID)).willReturn(
                new MemberDto(MEMBER_ID, "deleted@palette.com", "탈퇴회원", MemberRole.USER, MemberStatus.DELETED));

        assertThatThrownBy(() -> reviewFacade.createReview(MEMBER_ID, 105L, 5, "리뷰 내용은 열 글자 이상"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("401-UNAUTHORIZED");
        assertThat(reviewRepository.existsByOrderItemId(105L)).isFalse();
    }

    @Test
    @DisplayName("수정: 보낸 값만 바꾸고, 이전·새 별점을 담은 수정 이벤트를 발행한다. 목록에는 modified = true로 나온다")
    void modify() {
        confirmed(MEMBER_ID, 106L, PRODUCT_ID);
        Long reviewId = reviewFacade.createReview(MEMBER_ID, 106L, 3, "처음에는 그저 그랬어요.");

        reviewFacade.modifyReview(MEMBER_ID, reviewId, 5, null);

        ProductReviewDto review = productReviews(ReviewSort.LATEST).items().getFirst();
        assertThat(review.rating()).isEqualTo(5);
        assertThat(review.content()).isEqualTo("처음에는 그저 그랬어요.");
        assertThat(review.modified()).isTrue();
        assertThat(events("ReviewModifiedEvent", PRODUCT_ID)).hasSize(1);
    }

    @Test
    @DisplayName("수정·삭제: 다른 회원의 리뷰나 삭제된 리뷰면 404 REVIEW_NOT_FOUND")
    void modifyOrDeleteOthers() {
        confirmed(MEMBER_ID, 107L, PRODUCT_ID);
        Long reviewId = reviewFacade.createReview(MEMBER_ID, 107L, 4, "괜찮은 제품이에요. 추천해요.");

        assertThatThrownBy(() -> reviewFacade.modifyReview(OTHER_MEMBER_ID, reviewId, 1, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-REVIEW_NOT_FOUND");
        assertThatThrownBy(() -> reviewFacade.deleteReview(OTHER_MEMBER_ID, reviewId))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-REVIEW_NOT_FOUND");

        reviewFacade.deleteReview(MEMBER_ID, reviewId);

        assertThatThrownBy(() -> reviewFacade.deleteReview(MEMBER_ID, reviewId))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-REVIEW_NOT_FOUND");
        assertThatThrownBy(() -> reviewFacade.modifyReview(MEMBER_ID, reviewId, 5, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-REVIEW_NOT_FOUND");
        assertThat(events("ReviewDeletedEvent", PRODUCT_ID)).hasSize(1);
    }

    @Test
    @DisplayName("상품 리뷰 목록: 정렬별로 나오고 별점이 같으면 최신순이다. 삭제된 리뷰와 다른 상품의 리뷰는 빠진다")
    void productReviews() {
        Long first = write(MEMBER_ID, 111L, PRODUCT_ID, 4);
        Long second = write(MEMBER_ID, 112L, PRODUCT_ID, 2);
        Long third = write(OTHER_MEMBER_ID, 113L, PRODUCT_ID, 4);
        Long deleted = write(OTHER_MEMBER_ID, 114L, PRODUCT_ID, 5);
        write(MEMBER_ID, 115L, PRODUCT_ID + 1, 5);
        reviewFacade.deleteReview(OTHER_MEMBER_ID, deleted);

        assertThat(productReviews(ReviewSort.LATEST).items()).extracting(ProductReviewDto::reviewId)
                .containsExactly(third, second, first);
        assertThat(productReviews(ReviewSort.RATING_HIGH).items()).extracting(ProductReviewDto::reviewId)
                .containsExactly(third, first, second);
        assertThat(productReviews(ReviewSort.RATING_LOW).items()).extracting(ProductReviewDto::reviewId)
                .containsExactly(second, third, first);
        assertThat(productReviews(ReviewSort.LATEST).totalElements()).isEqualTo(3);
        assertThat(productReviews(ReviewSort.LATEST).items().getFirst()).satisfies(review -> {
            assertThat(review.reviewerName()).isEqualTo("김*진");
            assertThat(review.optionName()).isEqualTo("50ml");
            assertThat(review.modified()).isFalse();
        });
    }

    @Test
    @DisplayName("평점 요약: 삭제된 리뷰를 빼고 평균(소수 첫째 자리)·리뷰 수·별점별 개수(0개 포함, 5점부터)를 준다")
    void summary() {
        write(MEMBER_ID, 121L, PRODUCT_ID, 5);
        write(MEMBER_ID, 122L, PRODUCT_ID, 4);
        write(MEMBER_ID, 123L, PRODUCT_ID, 4);
        Long deleted = write(MEMBER_ID, 124L, PRODUCT_ID, 1);
        reviewFacade.deleteReview(MEMBER_ID, deleted);

        ReviewSummaryDto summary = reviewFacade.getReviewSummary(PRODUCT_ID);

        assertThat(summary.averageRating()).isEqualTo(4.3);   // 13 / 3 = 4.33…
        assertThat(summary.reviewCount()).isEqualTo(3);
        assertThat(summary.ratingCounts()).containsExactly(
                entry(5, 1L), entry(4, 2L), entry(3, 0L), entry(2, 0L), entry(1, 0L));
    }

    @Test
    @DisplayName("평점 요약: 리뷰가 없으면 평균 0.0, 리뷰 수 0, 별점별 개수는 모두 0")
    void summaryEmpty() {
        ReviewSummaryDto summary = reviewFacade.getReviewSummary(PRODUCT_ID);

        assertThat(summary.averageRating()).isZero();
        assertThat(summary.reviewCount()).isZero();
        assertThat(summary.ratingCounts()).hasSize(5).allSatisfy((rating, count) -> assertThat(count).isZero());
    }

    @Test
    @DisplayName("내 리뷰 목록: 최신순이고 상품명·대표 이미지는 현재 상품 정보로 채운다. 다른 회원·삭제한 리뷰는 빠진다")
    void myReviews() {
        Product product = productRepository.save(Product.create(300L,
                categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "리뷰 크림", "설명", 10_000));
        ProductOption option = saveOption(product, 10);
        given(orderApi.findConfirmedOrderItem(MEMBER_ID, 131L)).willReturn(Optional.of(
                new ReviewableOrderItemDto(131L, product.getId(), option.getId(), "기본", LocalDateTime.now())));
        Long mine = reviewFacade.createReview(MEMBER_ID, 131L, 5, "실제 상품에 쓴 리뷰입니다.");
        Long latest = write(MEMBER_ID, 132L, PRODUCT_ID, 3);
        Long deleted = write(MEMBER_ID, 133L, PRODUCT_ID, 1);
        write(OTHER_MEMBER_ID, 134L, PRODUCT_ID, 5);
        reviewFacade.deleteReview(MEMBER_ID, deleted);

        PageResponse<MyReviewDto> result = reviewFacade.getMyReviews(MEMBER_ID, PageRequest.of(0, 20));

        assertThat(result.items()).extracting(MyReviewDto::reviewId).containsExactly(latest, mine);
        assertThat(result.items().getLast()).satisfies(review -> {
            assertThat(review.orderItemId()).isEqualTo(131L);
            assertThat(review.productId()).isEqualTo(product.getId());
            assertThat(review.productName()).isEqualTo("리뷰 크림");
            assertThat(review.optionName()).isEqualTo("기본");
        });
        assertThat(result.items().getFirst().productName()).isNull();   // 상품 정보를 찾지 못한 리뷰
    }

    private void confirmed(long memberId, long orderItemId, long productId) {
        given(orderApi.findConfirmedOrderItem(memberId, orderItemId)).willReturn(Optional.of(
                new ReviewableOrderItemDto(orderItemId, productId, OPTION_ID, "50ml", LocalDateTime.now())));
    }

    private Long write(long memberId, long orderItemId, long productId, int rating) {
        confirmed(memberId, orderItemId, productId);
        return reviewFacade.createReview(memberId, orderItemId, rating, "리뷰 내용은 열 글자 이상입니다.");
    }

    private PageResponse<ProductReviewDto> productReviews(ReviewSort sort) {
        return reviewFacade.getProductReviews(PRODUCT_ID, sort, PageRequest.of(0, 20));
    }

    private static MemberDto member(long memberId, String name) {
        return new MemberDto(memberId, "member" + memberId + "@palette.com", name, MemberRole.USER, MemberStatus.ACTIVE);
    }

    private static Map.Entry<Integer, Long> entry(int rating, long count) {
        return Map.entry(rating, count);
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
}
