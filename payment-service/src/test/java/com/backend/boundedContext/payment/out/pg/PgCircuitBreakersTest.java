package com.backend.boundedContext.payment.out.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.support.TestPgCircuitBreakers;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * PG별 서킷 브레이커: 결과 모름만 실패로 세고, 기준(최근 10회 중 50%, 최소 5회)을 넘으면 사용 불가
 */
class PgCircuitBreakersTest {

    private final PgCircuitBreakers circuitBreakers = TestPgCircuitBreakers.create();

    @Test
    @DisplayName("처음에는 모든 PG를 쓸 수 있다")
    void initiallyAvailable() {
        assertThat(circuitBreakers.isAvailable(PgProvider.TOSS)).isTrue();
        assertThat(circuitBreakers.isAvailable(PgProvider.HECTO)).isTrue();
    }

    @Test
    @DisplayName("결과 모름이 최소 호출 수(5회) 동안 계속되면 그 PG만 사용 불가가 된다")
    void opensOnUnknown() {
        repeat(4, this::unknown);
        assertThat(circuitBreakers.isAvailable(PgProvider.TOSS)).isTrue();

        unknown();

        assertThat(circuitBreakers.isAvailable(PgProvider.TOSS)).isFalse();
        assertThat(circuitBreakers.isAvailable(PgProvider.HECTO)).isTrue();
    }

    @Test
    @DisplayName("실패율이 50% 미만이면 사용 가능하다 (테스트베드처럼 1/3이 끊겨도 열리지 않음)")
    void staysClosedBelowThreshold() {
        for (int i = 0; i < 3; i++) {
            unknown();
            success();
            success();
        }
        unknown();

        assertThat(circuitBreakers.isAvailable(PgProvider.TOSS)).isTrue();
    }

    @Test
    @DisplayName("카드 거절·거래 없음은 PG가 응답한 것이라 실패로 세지 않고, 설정 오류도 세지 않는다")
    void notCountedAsFailure() {
        repeat(5, () -> call(() -> { throw new PgDeclinedException("REJECT_CARD_PAYMENT", "한도 초과", null); }, PgDeclinedException.class));
        repeat(5, () -> call(() -> { throw new PgPaymentNotFoundException(new RuntimeException("ST06")); }, PgPaymentNotFoundException.class));
        repeat(5, () -> call(() -> { throw new PgConfigurationException("HECTO_CONFIGURATION_ERROR", new IllegalStateException()); }, PgConfigurationException.class));

        assertThat(circuitBreakers.isAvailable(PgProvider.TOSS)).isTrue();
    }

    @Test
    @DisplayName("직접 강제로 연(FORCED_OPEN) PG도 사용 불가다")
    void forcedOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        PgCircuitBreakers forced = new PgCircuitBreakers(registry);
        registry.circuitBreaker(PgCircuitBreakers.name(PgProvider.TOSS)).transitionToForcedOpenState();

        assertThat(forced.isAvailable(PgProvider.TOSS)).isFalse();
        assertThat(forced.isAvailable(PgProvider.HECTO)).isTrue();
    }

    @Test
    @DisplayName("호출 결과는 그대로 돌려준다")
    void returnsResult() {
        assertThat(circuitBreakers.record(PgProvider.TOSS, () -> "OK")).isEqualTo("OK");
    }

    @Test
    @DisplayName("열린 뒤 정해진 시간이 지나면 시험 호출을 받도록 다시 사용 가능해지고, 시험 호출이 성공하면 닫힌다")
    void halfOpenThenClose() {
        PgCircuitBreakers quick = TestPgCircuitBreakers.create(Duration.ofSeconds(1));
        repeat(5, () -> assertThatThrownBy(() -> quick.record(PgProvider.HECTO, unknownCall())).isInstanceOf(PgResultUnknownException.class));
        assertThat(quick.isAvailable(PgProvider.HECTO)).isFalse();

        await().atMost(Duration.ofSeconds(5)).until(() -> quick.isAvailable(PgProvider.HECTO));
        repeat(3, () -> quick.record(PgProvider.HECTO, () -> "OK"));

        assertThat(quick.isAvailable(PgProvider.HECTO)).isTrue();
    }

    private void unknown() {
        assertThatThrownBy(() -> circuitBreakers.record(PgProvider.TOSS, unknownCall())).isInstanceOf(PgResultUnknownException.class);
    }

    private void success() {
        circuitBreakers.record(PgProvider.TOSS, () -> "OK");
    }

    private void call(Supplier<Object> call, Class<? extends Throwable> expected) {
        assertThatThrownBy(() -> circuitBreakers.record(PgProvider.TOSS, call)).isInstanceOf(expected);
    }

    private static Supplier<Object> unknownCall() {
        return () -> { throw new PgResultUnknownException(new RuntimeException("Connection reset")); };
    }

    private static void repeat(int times, Runnable action) {
        for (int i = 0; i < times; i++) action.run();
    }
}
