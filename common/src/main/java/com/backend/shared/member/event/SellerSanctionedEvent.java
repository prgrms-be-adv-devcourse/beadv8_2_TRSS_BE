package com.backend.shared.member.event;

import com.backend.shared.event.DomainEvent;

// 관리자 판매자 제재. 상품 컨텍스트가 받아 판매 중 상품을 중지하고, 정산 컨텍스트가 받아 정산을 보류
public record SellerSanctionedEvent(
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
