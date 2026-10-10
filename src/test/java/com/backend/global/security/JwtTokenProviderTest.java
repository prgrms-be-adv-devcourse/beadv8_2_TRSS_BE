package com.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberRole;

class JwtTokenProviderTest {

    private static final String SECRET = base64("test-jwt-secret-key-at-least-32-bytes!!");
    private static final String ANOTHER_SECRET = base64("another-jwt-secret-key-at-least-32-bytes");

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(SECRET, 30);

    @Test
    @DisplayName("발급한 Access Token을 검증하면 회원 ID와 역할이 나온다")
    void createAndParse() {
        String token = jwtTokenProvider.createAccessToken(7L, MemberRole.SELLER);

        assertThat(jwtTokenProvider.parseAccessToken(token)).isEqualTo(new AuthMember(7L, MemberRole.SELLER));
    }

    @Test
    @DisplayName("만료된 토큰은 401 AUTH_TOKEN_EXPIRED")
    void expired() {
        String token = new JwtTokenProvider(SECRET, -1).createAccessToken(7L, MemberRole.USER);

        assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken(token))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(GlobalErrorCode.AUTH_TOKEN_EXPIRED.resultCode());
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401 UNAUTHORIZED")
    void forged() {
        String token = new JwtTokenProvider(ANOTHER_SECRET, 30).createAccessToken(7L, MemberRole.ADMIN);

        assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken(token))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(GlobalErrorCode.UNAUTHORIZED.resultCode());
    }

    @Test
    @DisplayName("JWT 형식이 아니면 401 UNAUTHORIZED")
    void malformed() {
        assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken("not-a-jwt"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(GlobalErrorCode.UNAUTHORIZED.resultCode());
    }

    @Test
    @DisplayName("Base64가 아니거나 디코딩 후 32바이트보다 짧은 키는 앱 시작 시 실패한다")
    void invalidSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider("not base64 !!", 30)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenProvider(base64("short"), 30)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenProvider("", 30)).isInstanceOf(IllegalStateException.class);
    }

    private static String base64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
