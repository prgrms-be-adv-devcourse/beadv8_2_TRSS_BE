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

    public MemberPolicy(
            @Value("${custom.member.emailCodeExpireMinutes}") long emailCodeExpireMinutes,
            @Value("${custom.member.emailCodeResendSeconds}") long emailCodeResendSeconds,
            @Value("${custom.member.emailVerifiedTokenExpireMinutes}") long emailVerifiedTokenExpireMinutes,
            @Value("${custom.member.emailCodeMaxAttempts}") int emailCodeMaxAttempts
    ) {
        this.emailCodeTtl = Duration.ofMinutes(emailCodeExpireMinutes);
        this.emailCodeResendInterval = Duration.ofSeconds(emailCodeResendSeconds);
        this.emailVerifiedTokenTtl = Duration.ofMinutes(emailVerifiedTokenExpireMinutes);
        this.emailCodeMaxAttempts = emailCodeMaxAttempts;
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
}
