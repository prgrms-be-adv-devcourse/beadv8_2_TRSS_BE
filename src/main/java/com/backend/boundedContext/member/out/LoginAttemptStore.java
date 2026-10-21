package com.backend.boundedContext.member.out;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 이메일별 로그인 실패 횟수를 Redis에 TTL로 저장 (가입하지 않은 이메일도 같이 센다)
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptStore {

    private static final String FAIL_COUNT_KEY = "member:login-fail:";

    private final StringRedisTemplate redisTemplate;

    // 현재 실패 횟수 (없거나 만료됐으면 0)
    public long getFailCount(String email) {
        String count = redisTemplate.opsForValue().get(FAIL_COUNT_KEY + email);
        return count == null ? 0 : Long.parseLong(count);
    }

    // 실패 횟수 증가 후 현재 횟수 반환 (첫 실패 때 TTL 설정)
    public long increaseFailCount(String email, Duration ttl) {
        String key = FAIL_COUNT_KEY + email;
        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, ttl);
        }
        return count == null ? 0 : count;
    }

    // 잠금 시작: 잠금 시간이 이 시점부터 다시 흐르도록 TTL을 새로 시작
    public void lock(String email, Duration ttl) {
        redisTemplate.expire(FAIL_COUNT_KEY + email, ttl);
    }

    public void deleteFailCount(String email) {
        redisTemplate.delete(FAIL_COUNT_KEY + email);
    }
}
