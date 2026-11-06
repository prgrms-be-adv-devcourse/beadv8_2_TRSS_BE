package com.backend.boundedContext.member.out;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Refresh Token을 Redis에 TTL로 저장 (key: 토큰, value: 회원 ID)
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY = "member:refresh-token:";

    private final StringRedisTemplate redisTemplate;

    public void save(String refreshToken, Long memberId, Duration ttl) {
        redisTemplate.opsForValue().set(KEY + refreshToken, String.valueOf(memberId), ttl);
    }

    // 조회와 동시에 삭제 (회전: 같은 토큰으로 두 번 재발급하지 못하게 원자적으로 꺼냄). 없거나 만료됐으면 empty
    public Optional<Long> consume(String refreshToken) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY + refreshToken))
                .map(Long::valueOf);
    }

    public void delete(String refreshToken) {
        redisTemplate.delete(KEY + refreshToken);
    }
}
