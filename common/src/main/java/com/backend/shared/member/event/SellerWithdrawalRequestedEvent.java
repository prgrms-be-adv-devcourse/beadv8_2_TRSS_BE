package com.backend.shared.member.event;

import com.backend.shared.event.DomainEvent;

// 판매자 철회 요청. 상품 컨텍스트가 받아 판매 중 상품을 중지
public record SellerWithdrawalRequestedEvent(
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
