package com.backend.shared.product.event;

import com.backend.shared.event.DomainEvent;

// 구매자가 상품 찜을 해제함. 찜하지 않은 상품을 해제하면 발행하지 않는다. 상품 검색 색인의 찜 수 반영에 쓴다
public record WishRemovedEvent(
        Long memberId,
        Long productId
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "PRODUCT";
    }

    @Override
    public Long aggregateId() {
        return productId;
    }
}
