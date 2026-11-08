package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.domain.Review;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.review.event.ReviewDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReviewDeleteReviewUseCase {
    private final ReviewSupport reviewSupport;
    private final EventPublisher eventPublisher;

    /**
     * 본인 리뷰를 지운다 (소프트 삭제). 지운 주문 항목에는 다시 리뷰를 쓸 수 없다.
     * 없거나 이미 삭제됐거나 다른 회원의 리뷰면 404 REVIEW_NOT_FOUND
     */
    public void deleteReview(Long memberId, Long reviewId) {
        Review review = reviewSupport.getMyReview(memberId, reviewId);

        review.delete(LocalDateTime.now());

        eventPublisher.publish(new ReviewDeletedEvent(review.getId(), review.getProductId(), review.getRating()));
    }
}
