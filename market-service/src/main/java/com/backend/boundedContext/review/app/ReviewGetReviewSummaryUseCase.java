package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.app.dto.ReviewSummaryDto;
import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.out.RatingCount;
import com.backend.boundedContext.review.out.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReviewGetReviewSummaryUseCase {
    private final ReviewRepository reviewRepository;

    /**
     * 상품 평점 요약. 별점별 개수를 한 번에 집계하고, 리뷰 수와 평균은 그 결과로 계산한다.
     * 없는 상품이거나 리뷰가 없으면 평균 0.0, 리뷰 수 0, 별점별 개수는 모두 0
     */
    public ReviewSummaryDto getReviewSummary(Long productId) {
        Map<Integer, Long> ratingCounts = new LinkedHashMap<>();
        for (int rating = Review.MAX_RATING; rating >= Review.MIN_RATING; rating--) {
            ratingCounts.put(rating, 0L);
        }
        for (RatingCount count : reviewRepository.countByRating(productId)) {
            ratingCounts.put(count.rating(), count.count());
        }

        long reviewCount = 0;
        long ratingSum = 0;
        for (Map.Entry<Integer, Long> entry : ratingCounts.entrySet()) {
            reviewCount += entry.getValue();
            ratingSum += entry.getKey() * entry.getValue();
        }
        double average = reviewCount == 0 ? 0.0 : Math.round(ratingSum * 10.0 / reviewCount) / 10.0;

        return new ReviewSummaryDto(average, reviewCount, ratingCounts);
    }
}
