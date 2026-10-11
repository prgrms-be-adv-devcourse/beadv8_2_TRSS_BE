package com.backend.global.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.backend.global.exception.DomainException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Authorization: Bearer 헤더의 Access Token을 검증해 인증 정보를 넣음
 * 토큰이 없거나 잘못돼도 여기서 막지 않고, 인증이 필요한 경로에서 EntryPoint가 401로 응답
 */
@RequiredArgsConstructor
public class JwtTokenFilter extends OncePerRequestFilter {

    // EntryPoint가 401 응답 코드(만료/위조)를 고를 때 쓰는 요청 속성
    public static final String AUTH_ERROR_ATTRIBUTE = "authError";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                AuthMember authMember = jwtTokenProvider.parseAccessToken(header.substring(BEARER_PREFIX.length()));
                var authority = new SimpleGrantedAuthority("ROLE_" + authMember.role().name());
                var authentication = new UsernamePasswordAuthenticationToken(authMember, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (DomainException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, e);
            }
        }

        filterChain.doFilter(request, response);
    }
}
