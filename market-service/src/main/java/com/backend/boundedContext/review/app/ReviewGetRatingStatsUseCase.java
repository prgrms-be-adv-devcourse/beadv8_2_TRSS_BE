package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.out.RatingCount;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 상품별 리뷰 수·별점 합 (상품 검색 색인의 별점 점수용, ReviewApi).
 * 상품 평점 요약(ReviewGetReviewSummaryUseCase)과 같은 별점별 집계로 계산해 두 값이 어긋나지 않게 한다
 */
@Service
@RequiredArgsConstructor
public class ReviewGetRatingStatsUseCase {
    private final ReviewRepository reviewRepository;

    /** 리뷰가 없는 상품은 키가 없다 */
    public Map<Long, ProductRatingStatsDto> getRatingStats(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        Map<Long, ProductRatingStatsDto> stats = new HashMap<>();
        for (RatingCount count : reviewRepository.countByRating(productIds)) {
            stats.merge(count.productId(),
                    new ProductRatingStatsDto(count.productId(), count.count(), count.rating() * count.count()),
                    (a, b) -> new ProductRatingStatsDto(a.productId(), a.reviewCount() + b.reviewCount(), a.ratingSum() + b.ratingSum()));
        }
        return stats;
    }
}
