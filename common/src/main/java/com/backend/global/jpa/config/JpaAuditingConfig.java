package com.backend.global.jpa.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * created_at, updated_at 자동 기록(JPA Auditing) 설정.
 * Application 클래스에 두면 @WebMvcTest 같은 슬라이스 테스트가 깨지므로 별도 설정 클래스로 분리한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
