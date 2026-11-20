package com.backend.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 서비스 간 호출(/internal/**)의 X-Internal-Key 헤더를 설정된 내부 키와 비교해 내부 호출자로 인증함
 * 키가 없거나 틀려도 여기서 막지 않고, 내부 API 체인의 EntryPoint가 401로 응답
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";

    // 지금은 모든 서비스가 같은 키를 씀. 서비스별 키로 바꾸면 키마다 호출한 서비스 이름을 둠
    private static final String CALLER = "internal";

    private final byte[] apiKey;

    public InternalApiKeyFilter(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("내부 API 키(custom.internal.apiKey)가 비어 있습니다");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);

        // 키 길이·내용에 따라 비교 시간이 달라지지 않게 비교함
        if (key != null && MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            var authority = new SimpleGrantedAuthority("ROLE_INTERNAL");
            var authentication = new UsernamePasswordAuthenticationToken(CALLER, null, List.of(authority));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
