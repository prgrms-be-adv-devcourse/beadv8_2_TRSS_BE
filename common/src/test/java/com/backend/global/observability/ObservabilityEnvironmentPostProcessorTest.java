package com.backend.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.support.EnvironmentPostProcessorsFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 관측 기본값(observability-defaults.properties)을 가장 낮은 우선순위로 붙이는지 본다.
 */
class ObservabilityEnvironmentPostProcessorTest {

    private static final String EXPOSURE = "management.endpoints.web.exposure.include";

    private final ObservabilityEnvironmentPostProcessor postProcessor = new ObservabilityEnvironmentPostProcessor();

    @Test
    @DisplayName("다른 설정이 없으면 health·prometheus만 노출하는 기본값이 들어간다")
    void appliesDefaults() {
        StandardEnvironment environment = new StandardEnvironment();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(EXPOSURE)).isEqualTo("health,prometheus");
        assertThat(environment.getProperty("management.metrics.distribution.slo.http.server.requests"))
                .isEqualTo("50ms,100ms,300ms,500ms,1s,3s");
    }

    @Test
    @DisplayName("서비스 설정·환경변수에 같은 키가 있으면 그 값이 이긴다(기본값은 가장 낮은 우선순위)")
    void serviceSettingWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("service", Map.of(EXPOSURE, "health")));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(EXPOSURE)).isEqualTo("health");
        assertThat(environment.getPropertySources().stream().reduce((first, second) -> second).orElseThrow().getName())
                .isEqualTo(ObservabilityEnvironmentPostProcessor.NAME);
    }

    @Test
    @DisplayName("application 태그는 서비스 이름(spring.application.name)을 따른다")
    void applicationTagFollowsServiceName() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("service", Map.of("spring.application.name", "market-service")));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.metrics.tags.application")).isEqualTo("market-service");
    }

    @Test
    @DisplayName("두 번 적용해도 기본값 소스는 하나다")
    void idempotent() {
        StandardEnvironment environment = new StandardEnvironment();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());
        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        long count = environment.getPropertySources().stream()
                .filter(source -> source.getName().equals(ObservabilityEnvironmentPostProcessor.NAME))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("spring.factories에 등록돼 모든 서비스 기동 때 자동으로 적용된다")
    void registeredInSpringFactories() {
        // SpringApplication이 기동 때 쓰는 것과 같은 경로로 spring.factories를 읽는다
        DeferredLogFactory logFactory = Supplier::get;
        assertThat(EnvironmentPostProcessorsFactory.fromSpringFactories(getClass().getClassLoader())
                .getEnvironmentPostProcessors(logFactory, new DefaultBootstrapContext()))
                .anyMatch(ObservabilityEnvironmentPostProcessor.class::isInstance);
    }
}
