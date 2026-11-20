package com.backend.global.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberRole;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 요청한 회원(AuthMember)을 Spring Security에 넣는다. 컨트롤러는 @AuthenticationPrincipal AuthMember로 꺼낸다.
 * <ul>
 *   <li>gateway가 넘긴 X-Member-Id·X-Member-Role 헤더가 있으면 그 값을 쓴다. gateway가 토큰을 열어 만든 값이고,
 *       밖에서 온 같은 이름 헤더는 gateway가 지우므로 믿는다(서비스 포트는 밖에 열려 있지 않다)</li>
 *   <li>헤더가 없고 acceptBearerToken이면 Authorization: Bearer 토큰을 직접 연다.
 *       gateway 없이 서비스를 부르는 로컬 개발·테스트용이다(기본 true, 운영은 compose에서 false)</li>
 * </ul>
 * 회원 정보를 못 만들어도 여기서 막지 않고, 인증이 필요한 경로에서 EntryPoint가 401로 응답한다.
 */
@RequiredArgsConstructor
public class JwtTokenFilter extends OncePerRequestFilter {

    // EntryPoint가 401 응답 코드(만료/위조)를 고를 때 쓰는 요청 속성
    public static final String AUTH_ERROR_ATTRIBUTE = "authError";

    // gateway(JwtAuthenticationFilter)가 넣는 회원 정보 헤더. 이름을 바꾸면 gateway도 같이 바꾼다
    public static final String MEMBER_ID_HEADER = "X-Member-Id";
    public static final String MEMBER_ROLE_HEADER = "X-Member-Role";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final boolean acceptBearerToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String memberId = request.getHeader(MEMBER_ID_HEADER);
        String role = request.getHeader(MEMBER_ROLE_HEADER);

        if (memberId != null || role != null) {
            // 헤더 값이 이상하면 회원 정보 없이 둔다(로그인 필요 경로면 401). gateway가 넣는 값이라 정상이면 생기지 않는다
            try {
                authenticate(new AuthMember(Long.valueOf(memberId), MemberRole.valueOf(role)));
            } catch (IllegalArgumentException | NullPointerException e) {
                SecurityContextHolder.clearContext();
            }
        } else if (acceptBearerToken) {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith(BEARER_PREFIX)) {
                try {
                    authenticate(jwtTokenProvider.parseAccessToken(header.substring(BEARER_PREFIX.length())));
                } catch (DomainException e) {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, e);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private static void authenticate(AuthMember authMember) {
        var authority = new SimpleGrantedAuthority("ROLE_" + authMember.role().name());
        var authentication = new UsernamePasswordAuthenticationToken(authMember, null, List.of(authority));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
