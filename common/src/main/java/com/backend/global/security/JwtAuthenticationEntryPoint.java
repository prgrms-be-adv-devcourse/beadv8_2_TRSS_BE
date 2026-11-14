package com.backend.global.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 인증 실패 401: 토큰 만료면 AUTH_TOKEN_EXPIRED, 토큰 없음·위조면 UNAUTHORIZED
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter securityErrorResponseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        // JwtTokenFilter가 토큰 검증 실패 사유를 남긴 경우 그 코드로 응답
        if (request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE) instanceof DomainException authError) {
            securityErrorResponseWriter.write(response, authError.getResultCode(), authError.getMsg());
            return;
        }
        securityErrorResponseWriter.write(response, GlobalErrorCode.UNAUTHORIZED);
    }
}
