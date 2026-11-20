package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.app.dto.MyReviewDto;
import com.backend.boundedContext.review.app.dto.ProductReviewDto;
import com.backend.boundedContext.review.app.dto.ReviewSummaryDto;
import com.backend.boundedContext.review.domain.ReviewSort;
import com.backend.global.page.PageResponse;
import com.backend.shared.review.dto.MemberRatingDto;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 리뷰 컨텍스트 진입점. 주문(구매 확정 여부)·회원(작성자 이름)·상품(상품명)은 공유 API로만 부른다.
 */
@Service
@RequiredArgsConstructor
public class ReviewFacade {
    private final ReviewCreateReviewUseCase reviewCreateReviewUseCase;
    private final ReviewModifyReviewUseCase reviewModifyReviewUseCase;
    private final ReviewDeleteReviewUseCase reviewDeleteReviewUseCase;
    private final ReviewGetProductReviewsUseCase reviewGetProductReviewsUseCase;
    private final ReviewGetReviewSummaryUseCase reviewGetReviewSummaryUseCase;
    private final ReviewGetMyReviewsUseCase reviewGetMyReviewsUseCase;
    private final ReviewGetRatingStatsUseCase reviewGetRatingStatsUseCase;
    private final ReviewGetMemberRatingsUseCase reviewGetMemberRatingsUseCase;

    /** 상품별 리뷰 수·별점 합 (ReviewApi, 상품 검색 색인의 별점 점수). 리뷰가 없는 상품은 키가 없다 */
    @Transactional(readOnly = true)
    public Map<Long, ProductRatingStatsDto> getRatingStats(Collection<Long> productIds) {
        return reviewGetRatingStatsUseCase.getRatingStats(productIds);
    }

    /** 회원이 쓴 리뷰의 별점, 최근 순 (ReviewApi, 개인화 추천) */
    @Transactional(readOnly = true)
    public List<MemberRatingDto> getMemberRatings(Long memberId) {
        return reviewGetMemberRatingsUseCase.getMemberRatings(memberId);
    }

    /** 구매 확정된 본인 주문 항목에 리뷰를 쓰고 리뷰 ID를 돌려준다 */
    @Transactional
    public Long createReview(Long memberId, Long orderItemId, int rating, String content) {
        return reviewCreateReviewUseCase.createReview(memberId, orderItemId, rating, content);
    }

    /** 본인 리뷰의 별점·내용 수정 (보낸 값만) */
    @Transactional
    public void modifyReview(Long memberId, Long reviewId, Integer rating, String content) {
        reviewModifyReviewUseCase.modifyReview(memberId, reviewId, rating, content);
    }

    /** 본인 리뷰 삭제 (소프트 삭제) */
    @Transactional
    public void deleteReview(Long memberId, Long reviewId) {
        reviewDeleteReviewUseCase.deleteReview(memberId, reviewId);
    }

    /** 상품의 리뷰 목록 (로그인 불필요) */
    @Transactional(readOnly = true)
    public PageResponse<ProductReviewDto> getProductReviews(Long productId, ReviewSort sort, Pageable pageable) {
        return reviewGetProductReviewsUseCase.getProductReviews(productId, sort, pageable);
    }

    /** 상품 평점 요약 (로그인 불필요) */
    @Transactional(readOnly = true)
    public ReviewSummaryDto getReviewSummary(Long productId) {
        return reviewGetReviewSummaryUseCase.getReviewSummary(productId);
    }

    /** 내 리뷰 목록 */
    @Transactional(readOnly = true)
    public PageResponse<MyReviewDto> getMyReviews(Long memberId, Pageable pageable) {
        return reviewGetMyReviewsUseCase.getMyReviews(memberId, pageable);
    }
}
