package com.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.backend.shared.member.dto.MemberRole;

/**
 * 요청한 회원을 gateway 헤더(X-Member-Id·X-Member-Role)로 만들고, 로컬·테스트에서만 토큰도 받는지 확인한다.
 */
class JwtTokenFilterTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("test-jwt-secret-key-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8));
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(SECRET, 30);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("gateway 헤더가 있으면 그 회원으로 인증한다")
    void memberFromGatewayHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtTokenFilter.MEMBER_ID_HEADER, "37");
        request.addHeader(JwtTokenFilter.MEMBER_ROLE_HEADER, "USER");

        filter(false, request);

        assertThat(authentication().getPrincipal()).isEqualTo(new AuthMember(37L, MemberRole.USER));
        assertThat(authentication().getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("헤더 값이 이상하면(숫자가 아닌 id, 없는 역할, 한쪽만 있음) 비회원으로 둔다")
    void invalidHeadersStayAnonymous() throws Exception {
        assertThat(authenticationOf("abc", "USER")).isNull();
        assertThat(authenticationOf("37", "SUPER")).isNull();
        assertThat(authenticationOf("37", null)).isNull();
        assertThat(authenticationOf(null, "ADMIN")).isNull();
    }

    @Test
    @DisplayName("운영 설정(acceptBearerToken=false)에서는 토큰만 와서는 인증하지 않는다")
    void bearerTokenIgnoredWhenNotAccepted() throws Exception {
        MockHttpServletRequest request = bearerRequest(jwtTokenProvider.createAccessToken(7L, MemberRole.ADMIN));

        filter(false, request);

        assertThat(authentication()).isNull();
    }

    @Test
    @DisplayName("로컬·테스트 설정(acceptBearerToken=true)에서는 헤더가 없으면 토큰으로 인증한다")
    void bearerTokenAcceptedWhenNoHeaders() throws Exception {
        MockHttpServletRequest request = bearerRequest(jwtTokenProvider.createAccessToken(7L, MemberRole.ADMIN));

        filter(true, request);

        assertThat(authentication().getPrincipal()).isEqualTo(new AuthMember(7L, MemberRole.ADMIN));
    }

    @Test
    @DisplayName("헤더와 토큰이 둘 다 오면 헤더의 회원을 쓴다")
    void headersWinOverToken() throws Exception {
        MockHttpServletRequest request = bearerRequest(jwtTokenProvider.createAccessToken(7L, MemberRole.ADMIN));
        request.addHeader(JwtTokenFilter.MEMBER_ID_HEADER, "37");
        request.addHeader(JwtTokenFilter.MEMBER_ROLE_HEADER, "USER");

        filter(true, request);

        assertThat(authentication().getPrincipal()).isEqualTo(new AuthMember(37L, MemberRole.USER));
    }

    @Test
    @DisplayName("토큰이 잘못되면 지금처럼 401 사유를 요청에 남긴다")
    void invalidTokenLeavesAuthError() throws Exception {
        MockHttpServletRequest request = bearerRequest("not-a-jwt");

        filter(true, request);

        assertThat(authentication()).isNull();
        assertThat(request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE)).isNotNull();
    }

    private Authentication authenticationOf(String memberId, String role) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (memberId != null) {
            request.addHeader(JwtTokenFilter.MEMBER_ID_HEADER, memberId);
        }
        if (role != null) {
            request.addHeader(JwtTokenFilter.MEMBER_ROLE_HEADER, role);
        }
        filter(true, request);
        Authentication authentication = authentication();
        SecurityContextHolder.clearContext();
        return authentication;
    }

    private void filter(boolean acceptBearerToken, MockHttpServletRequest request) throws Exception {
        new JwtTokenFilter(jwtTokenProvider, acceptBearerToken)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
