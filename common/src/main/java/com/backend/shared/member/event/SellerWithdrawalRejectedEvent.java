package com.backend.shared.member.event;

import com.backend.shared.event.DomainEvent;

// 관리자 판매자 철회 거절. 상품 컨텍스트가 받아 철회 요청 때 중지된 상품을 다시 판매 중으로 되돌림
public record SellerWithdrawalRejectedEvent(
        Long sellerId
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
