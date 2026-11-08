package com.backend.boundedContext.review.in;

import com.backend.boundedContext.review.app.ReviewFacade;
import com.backend.boundedContext.review.app.dto.ProductReviewDto;
import com.backend.boundedContext.review.app.dto.ReviewSummaryDto;
import com.backend.boundedContext.review.domain.ReviewSort;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "상품 리뷰", description = "상품 상세의 리뷰 목록과 평점 요약 (로그인 불필요)")
@RestController
@RequestMapping("/api/v1/products/{productId}/reviews")
@RequiredArgsConstructor
public class ApiV1ProductReviewController {
    private final ReviewFacade reviewFacade;

    @Operation(summary = "상품 리뷰 목록",
            description = "sort: LATEST(최신순, 기본)·RATING_HIGH(별점 높은순)·RATING_LOW(별점 낮은순). 별점이 같으면 최신순입니다. 삭제된 리뷰는 나오지 않습니다.")
    @GetMapping
    public RsData<PageResponse<ProductReviewDto>> getProductReviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "LATEST") ReviewSort sort,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "상품 리뷰 목록을 조회했습니다.",
                reviewFacade.getProductReviews(productId, sort, pageable));
    }

    @Operation(summary = "상품 평점 요약", description = "평균 별점(소수 첫째 자리), 리뷰 수, 별점별 리뷰 수(5점부터, 0개도 포함)입니다.")
    @GetMapping("/summary")
    public RsData<ReviewSummaryDto> getReviewSummary(@PathVariable Long productId) {
        return RsData.of("200-1", "상품 평점 요약을 조회했습니다.", reviewFacade.getReviewSummary(productId));
    }
}
