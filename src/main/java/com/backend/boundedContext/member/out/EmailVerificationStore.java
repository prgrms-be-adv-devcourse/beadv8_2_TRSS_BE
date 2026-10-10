package com.backend.boundedContext.member.out;

import java.time.Duration;
import java.util.Optional;

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
    private static final String FAIL_COUNT_KEY = "member:email-code-fail:";
    private static final String VERIFIED_TOKEN_KEY = "member:email-verified:";

    private final StringRedisTemplate redisTemplate;

    // 인증코드 저장 (이전 코드가 있으면 덮어씀)
    public void saveCode(String email, String code, Duration ttl) {
        redisTemplate.opsForValue().set(CODE_KEY + email, code, ttl);
    }

    // 저장된 인증코드 (만료됐거나 없으면 empty)
    public Optional<String> findCode(String email) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(CODE_KEY + email));
    }

    // 인증코드 삭제
    public void deleteCode(String email) {
        redisTemplate.delete(CODE_KEY + email);
    }

    // 인증코드 실패 횟수 증가 후 현재 횟수 반환 (첫 실패 때 TTL 설정)
    public long increaseFailCount(String email, Duration ttl) {
        String key = FAIL_COUNT_KEY + email;
        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, ttl);
        }
        return count == null ? 0 : count;
    }

    public void deleteFailCount(String email) {
        redisTemplate.delete(FAIL_COUNT_KEY + email);
    }

    // 인증 완료 토큰 저장 (key: 토큰, value: 인증된 이메일)
    public void saveVerifiedToken(String token, String email, Duration ttl) {
        redisTemplate.opsForValue().set(VERIFIED_TOKEN_KEY + token, email, ttl);
    }

    // 재발송 제한 (이미 걸려 있으면 false)
    public boolean lockResend(String email, Duration interval) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(RESEND_LOCK_KEY + email, "1", interval));
    }

    public void unlockResend(String email) {
        redisTemplate.delete(RESEND_LOCK_KEY + email);
    }
}
