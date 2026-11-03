package com.backend.global.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 메서드를 동작시키는 공통 설정
 *
 * Application 클래스에 두면 슬라이스 테스트(@WebMvcTest 등)에도 스케줄러가 켜지므로 별도 설정 클래스로 분리
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "custom.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}