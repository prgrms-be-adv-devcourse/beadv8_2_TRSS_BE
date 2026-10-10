package com.backend.boundedContext.member.out;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 이메일 인증 상태를 Redis에 TTL로 저장
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationStore {

    private static final String CODE_KEY = "member:email-code:";
    private static final String RESEND_LOCK_KEY = "member:email-code-resend-lock:";

    private final StringRedisTemplate redisTemplate;

    // 인증코드 저장 (이전 코드가 있으면 덮어씀)
    public void saveCode(String email, String code, Duration ttl) {
        redisTemplate.opsForValue().set(CODE_KEY + email, code, ttl);
    }

    // 인증코드 삭제
    public void deleteCode(String email) {
        redisTemplate.delete(CODE_KEY + email);
    }

    // 재발송 제한 (이미 걸려 있으면 false)
    public boolean lockResend(String email, Duration interval) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(RESEND_LOCK_KEY + email, "1", interval));
    }

    public void unlockResend(String email) {
        redisTemplate.delete(RESEND_LOCK_KEY + email);
    }
}
