package com.backend.shared.member.event;

// 회원 가입 완료. 결제 컨텍스트가 받아 예치금 지갑을 생성
public record MemberSignedUpEvent(
        Long memberId
) {}
