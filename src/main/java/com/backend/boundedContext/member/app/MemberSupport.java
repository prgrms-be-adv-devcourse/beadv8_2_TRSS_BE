package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

// 여러 UseCase가 함께 쓰는 회원 조회
@Component
@RequiredArgsConstructor
public class MemberSupport {

    private final MemberRepository memberRepository;

    // 회원 조회 (없으면 404 MEMBER_NOT_FOUND)
    public Member getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
