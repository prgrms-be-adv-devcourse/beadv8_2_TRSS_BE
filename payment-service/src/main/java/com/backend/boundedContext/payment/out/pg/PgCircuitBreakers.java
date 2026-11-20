package com.backend.boundedContext.payment.out.pg;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PgProvider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * PG별 서킷 브레이커. PG 호출 결과를 세어, 결과 모름이 계속되면 그 PG를 사용 불가(OPEN)로 봄
 * - 기준·실패로 셀 예외는 application.yaml의 resilience4j.circuitbreaker.instances.pg-{PG}에 있음
 *   (결과 모름만 실패, 카드 거절·거래 없음은 PG가 응답한 것이라 성공, 설정 오류는 세지 않음)
 * - @CircuitBreaker(AOP)처럼 호출을 막지 않고 결과만 셈. 사용 불가 PG를 피하는 건 충전 준비가 isAvailable로 판단함
 *   (승인은 사용자가 이미 결제창 인증을 마쳤고, 대사·취소는 돈이 걸린 정리 작업이라 계속해야 함)
 * - 브레이커는 스프링 연동 모듈의 레지스트리에서 받아, 상태·실패율이 Prometheus 메트릭으로 나감
 */
@Slf4j
@Component
public class PgCircuitBreakers {

    private final Map<PgProvider, CircuitBreaker> breakers = new EnumMap<>(PgProvider.class);

    // PG마다 레지스트리에서 브레이커를 꺼내(설정은 application.yaml의 pg-{PG}) 상태가 바뀔 때 로그를 남기도록 등록함
    public PgCircuitBreakers(CircuitBreakerRegistry registry) {
        for (PgProvider provider : PgProvider.values()) {
            CircuitBreaker breaker = registry.circuitBreaker(name(provider));
            breaker.getEventPublisher().onStateTransition(event -> logTransition(provider, event.getStateTransition()));
            breakers.put(provider, breaker);
        }
    }

    // 레지스트리의 브레이커 이름. 설정(resilience4j.circuitbreaker.instances)도 이 이름으로 둠
    public static String name(PgProvider provider) {
        return "pg-" + provider.name();
    }

    // 충전 준비에 쓸 수 있는 PG인지. OPEN·FORCED_OPEN(직접 강제로 연 상태)은 사용 불가이고, HALF_OPEN은 시험 호출이 필요해 사용 가능으로 봄
    public boolean isAvailable(PgProvider provider) {
        CircuitBreaker.State state = breakers.get(provider).getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    // PG 호출을 실행하고 결과를 셈. 예외는 그대로 다시 던짐
    public <T> T record(PgProvider provider, Supplier<T> call) {
        CircuitBreaker breaker = breakers.get(provider);
        long start = System.nanoTime();
        try {
            T result = call.get();
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException e) {
            // 실패로 셀지·성공으로 셀지·세지 않을지는 설정의 record-exceptions·ignore-exceptions로 정해짐
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw e;
        }
    }

    // OPEN은 충전이 다른 PG로 넘어가는 장애라 ERROR(Grafana 알림), 그 밖의 전환은 WARN
    private static void logTransition(PgProvider provider, CircuitBreaker.StateTransition transition) {
        if (transition.getToState() == CircuitBreaker.State.OPEN) {
            log.error("PG 호출 결과 모름이 계속돼 사용 불가로 바꿉니다. pg={}, {}", provider, transition);
        } else {
            log.warn("PG 서킷 브레이커 상태가 바뀌었습니다. pg={}, {}", provider, transition);
        }
    }
}
