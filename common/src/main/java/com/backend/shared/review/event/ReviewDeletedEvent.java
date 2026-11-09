package com.backend.shared.review.event;

import com.backend.shared.event.DomainEvent;

// 구매자가 리뷰를 삭제함 (소프트 삭제). rating은 삭제 직전 별점이라 집계 쪽에서 빼면 된다
public record ReviewDeletedEvent(
        Long reviewId,
        Long productId,
        int rating
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "REVIEW";
    }

    @Override
    public Long aggregateId() {
        return productId;
    }
}
