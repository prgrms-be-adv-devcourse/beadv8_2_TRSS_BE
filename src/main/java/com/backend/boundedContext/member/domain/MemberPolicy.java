package com.backend.boundedContext.member.domain;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MemberPolicy {

    private final Duration emailCodeTtl;
    private final Duration emailCodeResendInterval;
    private final Duration emailVerifiedTokenTtl;
    private final int emailCodeMaxAttempts;
    private final int loginMaxAttempts;
    private final Duration loginLockTtl;
    private final Duration refreshTokenTtl;
    private final boolean refreshCookieSecure;
    private final String refreshCookieSameSite;

    public MemberPolicy(
            @Value("${custom.member.emailCodeExpireMinutes}") long emailCodeExpireMinutes,
            @Value("${custom.member.emailCodeResendSeconds}") long emailCodeResendSeconds,
            @Value("${custom.member.emailVerifiedTokenExpireMinutes}") long emailVerifiedTokenExpireMinutes,
            @Value("${custom.member.emailCodeMaxAttempts}") int emailCodeMaxAttempts,
            @Value("${custom.member.loginMaxAttempts}") int loginMaxAttempts,
            @Value("${custom.member.loginLockMinutes}") long loginLockMinutes,
            @Value("${custom.member.refreshTokenExpireDays}") long refreshTokenExpireDays,
            @Value("${custom.member.refreshCookieSecure}") boolean refreshCookieSecure,
            @Value("${custom.member.refreshCookieSameSite}") String refreshCookieSameSite
    ) {
        this.emailCodeTtl = Duration.ofMinutes(emailCodeExpireMinutes);
        this.emailCodeResendInterval = Duration.ofSeconds(emailCodeResendSeconds);
        this.emailVerifiedTokenTtl = Duration.ofMinutes(emailVerifiedTokenExpireMinutes);
        this.emailCodeMaxAttempts = emailCodeMaxAttempts;
        this.loginMaxAttempts = loginMaxAttempts;
        this.loginLockTtl = Duration.ofMinutes(loginLockMinutes);
        this.refreshTokenTtl = Duration.ofDays(refreshTokenExpireDays);
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshCookieSameSite = refreshCookieSameSite;
    }

    // 이메일 인증코드 유효 시간
    public Duration emailCodeTtl() {
        return emailCodeTtl;
    }

    // 인증코드 재발송 제한 간격
    public Duration emailCodeResendInterval() {
        return emailCodeResendInterval;
    }

    // 인증 완료 토큰 유효 시간
    public Duration emailVerifiedTokenTtl() {
        return emailVerifiedTokenTtl;
    }

    // 인증코드 확인 최대 실패 횟수
    public int emailCodeMaxAttempts() {
        return emailCodeMaxAttempts;
    }

    // 로그인 최대 실패 횟수 (도달하면 잠금)
    public int loginMaxAttempts() {
        return loginMaxAttempts;
    }

    // 로그인 실패를 세는 기간이자 잠금 시간
    public Duration loginLockTtl() {
        return loginLockTtl;
    }

    // Refresh Token 유효 기간
    public Duration refreshTokenTtl() {
        return refreshTokenTtl;
    }

    // Refresh Token 쿠키 Secure 여부 (HTTPS 배포 시 true)
    public boolean refreshCookieSecure() {
        return refreshCookieSecure;
    }

    // Refresh Token 쿠키 SameSite (Lax / None)
    public String refreshCookieSameSite() {
        return refreshCookieSameSite;
    }
}
