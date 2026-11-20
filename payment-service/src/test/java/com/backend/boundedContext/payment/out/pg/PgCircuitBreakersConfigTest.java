package com.backend.boundedContext.payment.out.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.backend.boundedContext.payment.domain.PgProvider;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * application.yaml의 PG 서킷 브레이커 설정이 레지스트리에 그대로 들어가고 메트릭으로 나가는지 확인한다 (설정 키 오타 등).
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class PgCircuitBreakersConfigTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("PG마다 같은 기준(최근 10회 중 50%, 최소 5회, 열림 60초, 반열림 3회)이고, 결과 모름만 실패·설정 오류는 세지 않는다")
    void config() {
        for (PgProvider provider : PgProvider.values()) {
            CircuitBreakerConfig config = registry.circuitBreaker(PgCircuitBreakers.name(provider)).getCircuitBreakerConfig();

            assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
            assertThat(config.getSlidingWindowSize()).isEqualTo(10);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50);
            assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(60).toMillis());
            assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
            assertThat(config.getRecordExceptionPredicate().test(new PgResultUnknownException(new RuntimeException()))).isTrue();
            assertThat(config.getRecordExceptionPredicate().test(new PgDeclinedException("REJECT", "거절", null))).isFalse();
            assertThat(config.getIgnoreExceptionPredicate().test(new PgConfigurationException("CONFIG", new IllegalStateException()))).isTrue();
        }
    }

    @Test
    @DisplayName("PG마다 서킷 상태가 메트릭(resilience4j.circuitbreaker.state)으로 나간다")
    void metrics() {
        for (PgProvider provider : PgProvider.values()) {
            assertThat(meterRegistry.find("resilience4j.circuitbreaker.state").tag("name", PgCircuitBreakers.name(provider)).gauges()).isNotEmpty();
        }
    }
}
