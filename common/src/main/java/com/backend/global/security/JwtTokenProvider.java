package com.backend.global.security;

import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberRole;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Access Token(JWT) 발급·검증. Refresh Token은 JWT가 아닌 임의 문자열로 회원 컨텍스트가 Redis에 저장한다.
 */
@Component
public class JwtTokenProvider {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtTokenProvider(
            @Value("${custom.jwt.secret}") String secret,
            @Value("${custom.jwt.accessTokenExpireMinutes}") long accessTokenExpireMinutes
    ) {
        this.key = Keys.hmacShaKeyFor(decodeSecret(secret));
        this.accessTokenTtl = Duration.ofMinutes(accessTokenExpireMinutes);
    }

    // 서명 키는 무작위 바이트를 Base64로 인코딩한 값 (생성: openssl rand -base64 32)
    private static byte[] decodeSecret(String secret) {
        byte[] secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("custom.jwt.secret(JWT_SECRET)은 Base64 형식이어야 합니다. openssl rand -base64 32로 만드세요.", e);
        }
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "custom.jwt.secret(JWT_SECRET)은 디코딩 후 32바이트 이상이어야 합니다. .env에 JWT_SECRET= 처럼 빈 값이 있는지 확인하세요.");
        }
        return secretBytes;
    }

    // Access Token 발급 (subject: 회원 ID, claim: 역할)
    public String createAccessToken(Long memberId, MemberRole role) {
        Date now = new Date();

        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenTtl.toMillis()))
                .signWith(key)
                .compact();
    }

    // Access Token 검증 (만료: 401 AUTH_TOKEN_EXPIRED, 위조·형식 오류: 401 UNAUTHORIZED)
    public AuthMember parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new AuthMember(Long.valueOf(claims.getSubject()), MemberRole.valueOf(claims.get(ROLE_CLAIM, String.class)));
        } catch (ExpiredJwtException e) {
            throw new DomainException(GlobalErrorCode.AUTH_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new DomainException(GlobalErrorCode.UNAUTHORIZED);
        }
    }
}
