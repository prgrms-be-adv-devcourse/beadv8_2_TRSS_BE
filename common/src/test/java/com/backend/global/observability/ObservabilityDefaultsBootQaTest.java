package com.backend.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * [QA] 실제 SpringApplication 기동 경로에서 관측 기본값의 우선순위를 본다.
 * - spring.factories로 자동 적용되는지(EPP를 직접 부르지 않는다)
 * - 서비스 application.yaml, 환경변수(MANAGEMENT_...), 명령줄 인자가 같은 키를 주면 그쪽이 이기는지
 * - Boot가 실제로 읽는 방식(Binder)으로도 같은 값이 나오는지
 */
class ObservabilityDefaultsBootQaTest {

    private static final String EXPOSURE = "management.endpoints.web.exposure.include";
    private static final String APPLICATION_TAG = "management.metrics.tags.application";

    @Configuration(proxyBeanMethods = false)
    static class EmptyApp {
    }

    private static ConfigurableApplicationContext run(ConfigurableEnvironment environment, String... args) {
        SpringApplication application = new SpringApplication(EmptyApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        if (environment != null) {
            application.setEnvironment(environment);
        }
        return application.run(args);
    }

    private static Set<String> boundExposure(ConfigurableEnvironment environment) {
        return Binder.get(environment).bind(EXPOSURE, Bindable.setOf(String.class)).orElse(Set.of());
    }

    @Test
    @DisplayName("기동만 하면 spring.factories로 기본값이 붙고, 다른 설정 소스보다 뒤에 있다")
    void appliedOnStartup_lowestPriority() {
        try (ConfigurableApplicationContext context = run(null, "--spring.application.name=qa-service")) {
            ConfigurableEnvironment environment = context.getEnvironment();

            assertThat(environment.getProperty(EXPOSURE)).isEqualTo("health,prometheus");
            assertThat(boundExposure(environment)).containsExactlyInAnyOrder("health", "prometheus");
            assertThat(environment.getProperty(APPLICATION_TAG)).isEqualTo("qa-service");

            List<String> names = environment.getPropertySources().stream().map(PropertySource::getName).toList();
            assertThat(names).contains(ObservabilityEnvironmentPostProcessor.NAME);
            // 뒤에 올 수 있는 것은 Boot가 붙이는 applicationInfo(spring.application.pid 등), defaultProperties,
            // logCorrelation(추적이 켜지면 로그 줄에 traceId를 넣는 Boot 기본 설정)뿐이다
            int index = names.indexOf(ObservabilityEnvironmentPostProcessor.NAME);
            assertThat(names.subList(index + 1, names.size())).isSubsetOf("applicationInfo", "defaultProperties", "logCorrelation");
            assertThat(names.indexOf(ObservabilityEnvironmentPostProcessor.NAME))
                    .isGreaterThan(names.indexOf(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                    .isGreaterThan(names.indexOf("commandLineArgs"));
        }
    }

    @Test
    @DisplayName("서비스 application.yaml에 같은 키가 있으면 그 값이 이긴다(이름 태그도 yaml의 서비스 이름)")
    void serviceYamlWins(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("application.yaml");
        Files.writeString(yaml, """
                spring:
                  application:
                    name: yaml-service
                management:
                  endpoints:
                    web:
                      exposure:
                        include: health
                """);

        try (ConfigurableApplicationContext context = run(null, "--spring.config.location=file:" + yaml)) {
            ConfigurableEnvironment environment = context.getEnvironment();

            assertThat(environment.getProperty(EXPOSURE)).isEqualTo("health");
            assertThat(boundExposure(environment)).containsExactly("health");
            assertThat(environment.getProperty(APPLICATION_TAG)).isEqualTo("yaml-service");
            // yaml에 없는 키는 기본값이 그대로 들어간다
            assertThat(environment.getProperty("management.metrics.distribution.slo.http.server.requests"))
                    .isEqualTo("50ms,100ms,300ms,500ms,1s,3s");
        }
    }

    @Test
    @DisplayName("환경변수(MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE)가 있으면 그 값이 이긴다")
    void environmentVariableWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "health",
                                "SPRING_APPLICATION_NAME", "env-service")));

        try (ConfigurableApplicationContext context = run(environment)) {
            ConfigurableEnvironment env = context.getEnvironment();

            assertThat(env.getProperty(EXPOSURE)).isEqualTo("health");
            assertThat(boundExposure(env)).containsExactly("health");
            assertThat(env.getProperty(APPLICATION_TAG)).isEqualTo("env-service");
        }
    }

    @Test
    @DisplayName("명령줄 인자가 있으면 그 값이 이긴다")
    void commandLineWins() {
        try (ConfigurableApplicationContext context = run(null, "--management.endpoints.web.exposure.include=health")) {
            assertThat(context.getEnvironment().getProperty(EXPOSURE)).isEqualTo("health");
        }
    }
}
