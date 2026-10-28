package com.backend.boundedContext.member.app;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.RefreshTokenStore;
import com.backend.global.security.JwtTokenProvider;

import lombok.RequiredArgsConstructor;

// 로그인·재발급이 함께 쓰는 토큰 발급
@Component
@RequiredArgsConstructor
public class MemberTokenIssuer {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final MemberPolicy memberPolicy;

    // Access Token(JWT)과 Refresh Token(임의 문자열, Redis 저장) 발급
    public AuthTokens issue(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getRole());
        String refreshToken = generateRefreshToken();
        refreshTokenStore.save(refreshToken, member.getId(), memberPolicy.refreshTokenTtl());
        return new AuthTokens(accessToken, refreshToken);
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
