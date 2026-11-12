package com.backend.shared.member.event;

import com.backend.shared.event.DomainEvent;

// 인플루언서 자격 해제 (관리자 해제·회원 탈퇴). 쿠폰 컨텍스트가 받아 그 인플루언서의 협약을 종료하고 상품 쿠폰을 중지
public record InfluencerRevokedEvent(
        Long influencerId,
        Long memberId,
        String reason
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "INFLUENCER";
    }

    @Override
    public Long aggregateId() {
        return influencerId;
    }
}
