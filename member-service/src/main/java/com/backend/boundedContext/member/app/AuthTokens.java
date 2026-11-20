package com.backend.boundedContext.member.app;

// 로그인·재발급 결과 (Access Token은 응답 바디, Refresh Token은 쿠키로 내려감)
public record AuthTokens(
        String accessToken,
        String refreshToken
) {}
