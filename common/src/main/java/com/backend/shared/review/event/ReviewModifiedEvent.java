package com.backend.shared.review.event;

import com.backend.shared.event.DomainEvent;

// 구매자가 리뷰를 수정함. 별점이 그대로여도 발행한다 (내용만 바뀐 경우). 집계 쪽은 oldRating → rating 차이만 반영하면 된다
public record ReviewModifiedEvent(
        Long reviewId,
        Long productId,
        int oldRating,
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
