package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.review.dto.MemberRatingDto;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import com.backend.shared.review.out.ReviewApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/** 상품별 리뷰 수·별점 합(ReviewApi): 삭제한 리뷰는 빼고, 리뷰가 없는 상품은 결과에 없다. 테스트마다 롤백한다 */
@SpringBootTest
@Transactional
class ReviewRatingStatsTest {

    private static final long PRODUCT_A = 9_920_001L;
    private static final long PRODUCT_B = 9_920_002L;

    @Autowired
    private ReviewApi reviewApi;

    @Autowired
    private ReviewRepository reviewRepository;

    /** 다른 상품·리뷰 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    @Test
    @DisplayName("상품별 리뷰 수와 별점 합을 세고, 삭제한 리뷰와 리뷰 없는 상품은 뺀다")
    void ratingStats() {
        save(1, PRODUCT_A, 5);
        save(2, PRODUCT_A, 3);
        Review deleted = save(3, PRODUCT_A, 1);
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        save(4, PRODUCT_B, 4);
        reviewRepository.flush();

        assertThat(reviewApi.getRatingStats(List.of(PRODUCT_A, PRODUCT_B, 9_920_999L)))
                .containsOnly(
                        entry(PRODUCT_A, new ProductRatingStatsDto(PRODUCT_A, 2, 8)),
                        entry(PRODUCT_B, new ProductRatingStatsDto(PRODUCT_B, 1, 4)));
    }

    @Test
    @DisplayName("회원이 준 별점: 그 회원의 삭제되지 않은 리뷰만 상품·별점·수정 시각으로 준다")
    void memberRatings() {
        save(11, PRODUCT_A, 5);
        save(12, PRODUCT_B, 2);
        Review deleted = save(13, 9_920_003L, 4);
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        reviewRepository.save(Review.create(9_920_014L, 9_921L, PRODUCT_A, 1L, "기본", "다른 회원", 1, "별로"));
        reviewRepository.flush();

        assertThat(reviewApi.getMemberRatings(9_920L))
                .extracting(MemberRatingDto::productId, MemberRatingDto::rating)
                .containsExactlyInAnyOrder(tuple(PRODUCT_A, 5), tuple(PRODUCT_B, 2));
        assertThat(reviewApi.getMemberRatings(9_920L)).allSatisfy(r -> assertThat(r.ratedAt()).isNotNull());
    }

    private Review save(long seq, long productId, int rating) {
        return reviewRepository.save(Review.create(9_920_000L + seq, 9_920L, productId, 1L, "기본", "리뷰어", rating, "좋아요"));
    }
}
