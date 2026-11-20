package com.backend.shared.member.event;

import com.backend.shared.event.DomainEvent;

// 관리자 판매자 제재 해지 (SANCTIONED → APPROVED). 정산은 제재로 보류한 정산서를, 상품은 제재로 중지한 상품을 되돌릴 때 받는다
public record SellerUnsanctionedEvent(
        Long sellerId,
        String reason
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
