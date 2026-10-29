package com.backend.boundedContext.member.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 로그인 시도 횟수 키에 항상 TTL이 붙는지 실제 Redis로 확인한다. 로컬 Docker Redis가 필요하다.
 */
@SpringBootTest
class LoginAttemptStoreTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Autowired
    private LoginAttemptStore loginAttemptStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String email = "login-attempt-it-" + UUID.randomUUID() + "@palette.com";
    private final String key = LoginAttemptStore.ATTEMPT_COUNT_KEY + email;

    @AfterEach
    void cleanUp() {
        loginAttemptStore.deleteAttempts(email);
    }

    @Test
    @DisplayName("첫 시도에 TTL과 함께 만들어지고, 다음 시도는 횟수만 올리고 TTL을 유지한다")
    void increaseAttempt_setsTtlOnce() {
        assertThat(loginAttemptStore.increaseAttempt(email, TTL)).isEqualTo(1);
        redisTemplate.expire(key, Duration.ofMinutes(3));   // 시간이 흐른 것처럼 TTL을 줄여 둔다

        assertThat(loginAttemptStore.increaseAttempt(email, TTL)).isEqualTo(2);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 180L);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("2");
    }

    @Test
    @DisplayName("TTL이 없는 키(이전 방식에서 INCR 뒤 EXPIRE 전에 장애가 난 경우)도 다음 시도에서 TTL이 붙어 영구 잠금이 되지 않는다")
    void increaseAttempt_healsKeyWithoutTtl() {
        redisTemplate.opsForValue().set(key, "5");
        assertThat(redisTemplate.getExpire(key)).isEqualTo(-1);

        assertThat(loginAttemptStore.increaseAttempt(email, TTL)).isEqualTo(6);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, TTL.toSeconds());
    }
}
