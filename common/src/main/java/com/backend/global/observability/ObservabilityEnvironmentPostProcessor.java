package com.backend.global.observability;

import java.io.IOException;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * common의 observability-defaults.properties를 가장 낮은 우선순위로 붙인다.
 * 서비스 application.yaml·환경변수·테스트 설정이 같은 키를 쓰면 언제나 그쪽이 이긴다.
 * 서비스 파일을 고치지 않고 모든 서비스에 같은 관측 설정을 주려고 둔다.
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String NAME = "paletteObservabilityDefaults";
    private static final String LOCATION = "observability-defaults.properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(NAME)) return;
        try {
            environment.getPropertySources().addLast(new ResourcePropertySource(NAME, new ClassPathResource(LOCATION)));
        } catch (IOException e) {
            throw new IllegalStateException("관측 기본값 파일을 읽지 못했습니다: " + LOCATION, e);
        }
    }
}
