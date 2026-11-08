package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.app.dto.MyReviewDto;
import com.backend.boundedContext.review.app.dto.ProductReviewDto;
import com.backend.boundedContext.review.app.dto.ReviewSummaryDto;
import com.backend.boundedContext.review.domain.ReviewSort;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
