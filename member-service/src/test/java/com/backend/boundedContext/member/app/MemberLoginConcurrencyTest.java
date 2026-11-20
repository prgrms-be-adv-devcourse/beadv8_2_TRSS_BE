package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.LoginAttemptStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;

/**
 * 같은 이메일로 틀린 비밀번호가 동시에 들어와도 비밀번호 비교는 최대 횟수(5)까지만 일어나는지 확인한다.
 * 시도 횟수를 비밀번호 확인 전에 올리므로, INCR 결과가 1~10으로 나뉘어 6번째부터는 비교 없이 잠금으로 거절된다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class MemberLoginConcurrencyTest {

    private static final int THREADS = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final String PASSWORD = "palette123!";

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private LoginAttemptStore loginAttemptStore;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private String email;
    private Long memberId;

    @BeforeEach
    void setUp() {
        email = "login-concurrency-it-" + UUID.randomUUID() + "@palette.com";
        memberId = memberRepository.save(Member.signUp(email, passwordEncoder.encode(PASSWORD), "회원")).getId();
        clearInvocations(passwordEncoder);
    }

    @AfterEach
    void cleanUp() {
        loginAttemptStore.deleteAttempts(email);
        memberRepository.deleteById(memberId);
    }

    @Test
    @DisplayName("틀린 비밀번호 10개를 동시에 보내면 비밀번호 비교는 5번만 일어나고, 나머지는 비교 없이 429 LOGIN_LOCKED")
    void concurrentWrongPasswords_compareOnlyMaxAttempts() throws InterruptedException {
        Map<String, AtomicInteger> results = runConcurrently(i -> memberFacade.login(email, "wrong-" + i));

        verify(passwordEncoder, times(MAX_ATTEMPTS)).matches(anyString(), anyString());
        assertThat(results.get("401-LOGIN_FAILED")).hasValue(MAX_ATTEMPTS - 1);
        assertThat(results.get("429-LOGIN_LOCKED")).hasValue(THREADS - MAX_ATTEMPTS + 1);   // 5번째 실패(잠금 시작) + 비교 없이 거절된 5개
        assertThat(results).containsOnlyKeys("401-LOGIN_FAILED", "429-LOGIN_LOCKED");
    }

    private Map<String, AtomicInteger> runConcurrently(java.util.function.IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Map<String, AtomicInteger> results = new ConcurrentHashMap<>();

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            pool.submit(() -> {
                String result;
                try {
                    ready.countDown();
                    start.await();
                    task.accept(index);
                    result = "SUCCESS";
                } catch (DomainException e) {
                    result = e.getResultCode();
                } catch (Exception e) {
                    result = e.getClass().getSimpleName();
                }
                results.computeIfAbsent(result, key -> new AtomicInteger()).incrementAndGet();
                done.countDown();
            });
        }

        ready.await();
        start.countDown();   // 출발
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        return results;
    }
}
