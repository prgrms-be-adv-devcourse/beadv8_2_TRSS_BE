package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.app.dto.ProductReviewDto;
import com.backend.boundedContext.review.domain.ReviewSort;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReviewGetProductReviewsUseCase {
    private final ReviewRepository reviewRepository;

    /**
     * 상품의 리뷰 목록 (삭제된 리뷰 제외). 정렬은 요청의 sort 파라미터가 아니라 {@link ReviewSort}로만 정한다.
     * 없는 상품이면 빈 목록 (상품 존재 확인을 위해 상품 컨텍스트를 한 번 더 부르지 않는다)
     */
    public PageResponse<ProductReviewDto> getProductReviews(Long productId, ReviewSort sort, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.toSort());
        return PageResponse.from(reviewRepository.findActivePageByProductId(productId, sorted), ProductReviewDto::from);
    }
}
