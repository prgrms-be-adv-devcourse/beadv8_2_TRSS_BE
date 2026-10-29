package com.backend.shared.member.event;

import com.backend.shared.event.DomainEvent;

// 회원 가입 완료. 결제 컨텍스트가 받아 예치금 지갑을 생성
public record MemberSignedUpEvent(
        Long memberId
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "MEMBER";
    }

    @Override
    public Long aggregateId() {
        return memberId;
    }
}
