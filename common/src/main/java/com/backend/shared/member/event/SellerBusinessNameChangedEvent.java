package com.backend.shared.member.event;

import com.backend.shared.event.DomainEvent;

// 승인된 판매자가 상호를 바꿈 (실제로 바뀔 때만, 재신청으로 바뀌는 상호는 제외). 상품 검색 색인이 그 판매자 상품의 상호명을 다시 색인할 때 받는다
public record SellerBusinessNameChangedEvent(
        Long sellerId,
        String businessName
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "SELLER";
    }

    @Override
    public Long aggregateId() {
        return sellerId;
    }
}
