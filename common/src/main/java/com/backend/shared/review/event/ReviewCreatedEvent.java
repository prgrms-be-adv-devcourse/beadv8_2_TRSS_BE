package com.backend.shared.review.event;

import com.backend.shared.event.DomainEvent;

// 구매자가 리뷰를 작성함. 상품별 평점·리뷰 수 집계와 상품 검색 색인에 쓴다 (같은 상품의 이벤트 순서를 지키려고 key는 productId)
public record ReviewCreatedEvent(
        Long reviewId,
        Long productId,
        int rating,
        Long memberId   // 리뷰를 쓴 회원 (개인화 추천을 다시 계산할 회원). 이 필드를 더하기 전에 발행한 메시지는 null
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
