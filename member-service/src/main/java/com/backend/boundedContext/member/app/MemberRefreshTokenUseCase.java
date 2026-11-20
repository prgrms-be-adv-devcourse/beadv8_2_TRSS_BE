package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.RefreshTokenStore;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberRefreshTokenUseCase {

    private final RefreshTokenStore refreshTokenStore;
    private final MemberRepository memberRepository;
    private final MemberTokenIssuer memberTokenIssuer;

    // 기존 Refresh Token을 폐기하고 새 토큰 발급 (회전). 토큰이 없거나 만료·폐기됐거나 탈퇴 회원이면 401 AUTH_TOKEN_EXPIRED
    public AuthTokens refresh(String refreshToken) {
        Long memberId = refreshTokenStore.consume(refreshToken)
                .orElseThrow(() -> new DomainException(GlobalErrorCode.AUTH_TOKEN_EXPIRED));

        Member member = memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new DomainException(GlobalErrorCode.AUTH_TOKEN_EXPIRED));

        return memberTokenIssuer.issue(member);
    }
}
