package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.domain.Review;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.review.event.ReviewModifiedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReviewModifyReviewUseCase {
    private final ReviewSupport reviewSupport;
    private final EventPublisher eventPublisher;

    /**
     * 본인 리뷰의 별점·내용을 고친다. 보낸 값만 바꾼다 (null이면 그대로).
     * 없거나 삭제됐거나 다른 회원의 리뷰면 404 REVIEW_NOT_FOUND
     */
    public void modifyReview(Long memberId, Long reviewId, Integer rating, String content) {
        Review review = reviewSupport.getMyReview(memberId, reviewId);
        int oldRating = review.getRating();

        review.modify(rating, content);

        eventPublisher.publish(new ReviewModifiedEvent(review.getId(), review.getProductId(), oldRating, review.getRating()));
    }
}
