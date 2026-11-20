package com.backend.boundedContext.member.in;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.MemberPolicy;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Refresh Token 쿠키 (HttpOnly, 인증 API 경로에만 전송)
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookie {

    public static final String NAME = "refreshToken";
    private static final String PATH = "/api/v1/auth";

    private final MemberPolicy memberPolicy;

    public void add(HttpServletResponse response, String refreshToken) {
        write(response, refreshToken, memberPolicy.refreshTokenTtl().toSeconds());
    }

    public void clear(HttpServletResponse response) {
        write(response, "", 0);
    }

    private void write(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(memberPolicy.refreshCookieSecure())
                .sameSite(memberPolicy.refreshCookieSameSite())
                .path(PATH)
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
