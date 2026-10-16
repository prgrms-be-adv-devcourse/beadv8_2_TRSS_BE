package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetWithdrawalCheckUseCase {

    private final MemberSupport memberSupport;
    private final MemberWithdrawalSupport memberWithdrawalSupport;

    // 탈퇴 조건 조회 (화면 안내용. 실제 판단은 탈퇴 요청에서 다시 함)
    public WithdrawalCheckDto getWithdrawalCheck(Long memberId) {
        memberSupport.getLoginMember(memberId);
        return memberWithdrawalSupport.check(memberId);
    }
}
