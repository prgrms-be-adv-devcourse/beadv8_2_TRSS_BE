package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.kakao.KakaoOAuthClient;

import lombok.RequiredArgsConstructor;

/**
 * 카카오 계정 연결
 * 1. 프론트가 받은 인가 코드로 카카오 회원번호 조회 (카카오 API 호출, 트랜잭션 밖)
 * 2. 로그인한 회원에게 회원번호 연결 (짧은 트랜잭션, MemberSocialAccountLinker)
 * 연결 요청은 로그인 상태의 API 호출이라 Access Token으로 회원을 바로 알 수 있음
 */
@Component
@RequiredArgsConstructor
public class MemberLinkKakaoUseCase {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final MemberSocialAccountLinker memberSocialAccountLinker;

    public void link(Long memberId, String authorizationCode) {
        String kakaoUserId = kakaoOAuthClient.getUserId(authorizationCode);
        memberSocialAccountLinker.link(memberId, SocialProvider.KAKAO, kakaoUserId);
    }
}
