package com.backend.boundedContext.member.in.dto;

// 로그인·재발급 응답 (Refresh Token은 HttpOnly 쿠키로 내려간다)
public record AccessTokenResponse(String accessToken) {
}
