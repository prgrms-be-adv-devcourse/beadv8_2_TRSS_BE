package com.backend.boundedContext.member.out;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 이메일별 로그인 시도 횟수를 Redis에 TTL로 저장 (가입하지 않은 이메일도 같이 센다)
 * 성공하면 지우므로, 남아 있는 횟수는 잠금 시간 안의 실패 횟수(+ 처리 중인 시도)임
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptStore {

    // 테스트에서 Redis 값을 직접 확인할 때도 이 접두어를 씀
    public static final String ATTEMPT_COUNT_KEY = "member:login-fail:";

    // INCR과 TTL 설정을 한 명령으로 실행해, 그 사이 장애가 나도 TTL 없는 키(영구 잠금)가 남지 않게 함
    // TTL이 없는 키(이전 방식에서 남은 키)도 여기서 TTL을 붙여 풀리게 함. INCR은 기존 TTL을 유지함
    private static final RedisScript<Long> INCREASE_ATTEMPT_SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('PTTL', KEYS[1]) == -1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    // 비밀번호를 확인하기 전에 시도 횟수를 올리고 올린 값을 반환 (첫 시도 때 TTL 설정)
    // 동시에 요청이 와도 INCR 결과가 1, 2, 3...으로 하나씩 나뉘어, 최대 횟수를 넘은 요청은 비밀번호를 확인하지 않음
    public long increaseAttempt(String email, Duration ttl) {
        Long count = redisTemplate.execute(INCREASE_ATTEMPT_SCRIPT, List.of(ATTEMPT_COUNT_KEY + email), String.valueOf(ttl.toMillis()));
        return count == null ? 0 : count;
    }

    // 잠금 시작: 잠금 시간이 이 시점부터 다시 흐르도록 TTL을 새로 시작
    public void lock(String email, Duration ttl) {
        redisTemplate.expire(ATTEMPT_COUNT_KEY + email, ttl);
    }

    public void deleteAttempts(String email) {
        redisTemplate.delete(ATTEMPT_COUNT_KEY + email);
    }
}
