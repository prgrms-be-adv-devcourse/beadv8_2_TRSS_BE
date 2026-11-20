package com.backend.shared.product.event;

import com.backend.shared.event.DomainEvent;

// 구매자가 상품을 찜함. 이미 찜한 상품을 다시 찜하면 발행하지 않는다. 상품 검색 색인의 찜 수 반영에 쓴다
public record WishAddedEvent(
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
