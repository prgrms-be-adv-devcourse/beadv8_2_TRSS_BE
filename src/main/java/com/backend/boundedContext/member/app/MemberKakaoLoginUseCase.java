package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.boundedContext.member.out.kakao.KakaoOAuthClient;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 카카오 로그인 (연결 전용)
 * 1. 프론트가 받은 인가 코드로 카카오 회원번호 조회 (카카오 API 호출, 트랜잭션 밖)
 * 2. 회원번호로 연결된 회원을 찾아 이메일 로그인과 같은 토큰 발급
 * 카카오로는 가입하지 않으므로, 연결된 회원이 없으면 401 SOCIAL_NOT_LINKED
 */
@Component
@RequiredArgsConstructor
public class MemberKakaoLoginUseCase {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final MemberSocialAccountRepository memberSocialAccountRepository;
    private final MemberRepository memberRepository;
    private final MemberTokenIssuer memberTokenIssuer;

    public AuthTokens login(String authorizationCode) {
        String kakaoUserId = kakaoOAuthClient.getUserId(authorizationCode);

        Long memberId = memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, kakaoUserId)
                .map(account -> account.getMember().getId())   // 연결 정보의 회원 ID만 꺼냄 (회원 엔티티는 아래에서 다시 조회)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SOCIAL_NOT_LINKED));

        // 탈퇴하면 연결 정보도 지우지만, 남아 있더라도 이메일 로그인처럼 401 LOGIN_FAILED로 막음
        Member member = memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new DomainException(MemberErrorCode.LOGIN_FAILED));

        return memberTokenIssuer.issue(member);
    }
}
