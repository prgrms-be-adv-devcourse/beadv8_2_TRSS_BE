package com.backend.boundedContext.chat.out;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 회원별 챗봇 요청 횟수를 Redis에 TTL로 저장함(고정 시간 창). 첫 요청부터 window가 지나면 키가 사라져 다시 0부터 셈
 */
@Component
@RequiredArgsConstructor
public class ChatRateLimitStore {

    // 테스트에서 Redis 값을 직접 지울 때도 이 접두어를 씀
    public static final String REQUEST_COUNT_KEY = "ai:chat-request:";

    // INCR과 TTL 설정을 한 명령으로 실행해, 그 사이 장애가 나도 TTL 없는 키(영구 제한)가 남지 않게 함
    // 첫 요청에만 TTL을 설정하고, 이후 INCR은 기존 TTL을 유지함
    private static final RedisScript<Long> INCREASE_SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    // 요청 횟수를 올리고 올린 값을 반환. 동시에 요청이 와도 1, 2, 3...으로 하나씩 나뉨
    public long increase(Long memberId, Duration window) {
        Long count = redisTemplate.execute(INCREASE_SCRIPT, List.of(REQUEST_COUNT_KEY + memberId), String.valueOf(window.toMillis()));
        return count == null ? 0 : count;
    }
}
