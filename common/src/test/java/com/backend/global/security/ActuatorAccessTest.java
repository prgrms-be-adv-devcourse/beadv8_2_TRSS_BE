package com.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * actuator 경로 권한 (SecurityConfig)
 * - GET /actuator/health(/**)·/actuator/prometheus : 인증 없이 통과(Alloy 스크랩·헬스체크는 헤더를 보내지 않는다)
 * - 그 밖의 actuator 경로·POST : 인증 필요(노출 설정이 넓어져도 env·heapdump 등이 무인증으로 열리지 않게)
 * - 다른 경로·내부 체인(/internal/**)은 그대로
 * 경로 규칙만 보려고 테스트 전용 컨트롤러를 쓴다. 실제 actuator 응답(노출 엔드포인트·지표 내용)은
 * 로컬 bootRun·컨테이너에서 확인한다(설계 명세 4.1 테스트 6·7).
 */
@WebMvcTest(ActuatorAccessTest.StubController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class,
        ActuatorAccessTest.StubController.class})
@TestPropertySource(properties = {
        "custom.jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWtleS1hdC1sZWFzdC0zMi1ieXRlcyEh",   // Base64("test-jwt-secret-key-at-least-32-bytes!!")
        "custom.jwt.accessTokenExpireMinutes=30",
        "custom.internal.apiKey=test-internal-key",
        "custom.cors.allowedOrigins=http://localhost:3000"
})
class ActuatorAccessTest {

    private static final String PROMETHEUS_URL = "/actuator/prometheus";
    private static final String HEALTH_URL = "/actuator/health/x";
    private static final String USER_API_URL = "/api/v1/orders/ping";
    private static final String INTERNAL_API_URL = "/internal/v1/ping";
    private static final String ENV_URL = "/actuator/env";   // 허용 목록에 없는 actuator 경로

    @Autowired
    private MockMvc mockMvc;

    // common에는 @SpringBootApplication이 없어 테스트가 쓸 설정 클래스를 둔다
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @RestController
    static class StubController {

        @GetMapping(PROMETHEUS_URL)
        String prometheus() {
            return "ok";
        }

        @GetMapping(HEALTH_URL)
        String health() {
            return "ok";
        }

        @GetMapping(USER_API_URL)
        String userApi() {
            return "ok";
        }

        @GetMapping(INTERNAL_API_URL)
        String internalApi() {
            return "ok";
        }

        @GetMapping(ENV_URL)
        String env() {
            return "ok";
        }

        @PostMapping(PROMETHEUS_URL)
        String prometheusPost() {
            return "ok";
        }
    }

    @Test
    @DisplayName("토큰 없이 /actuator/prometheus를 부르면 통과한다")
    void prometheus_anonymous_allowed() throws Exception {
        mockMvc.perform(get(PROMETHEUS_URL))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 없이 /actuator/health 하위 경로를 부르면 통과한다")
    void health_anonymous_allowed() throws Exception {
        mockMvc.perform(get(HEALTH_URL))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("잘못된 Bearer 토큰이 붙어도 actuator는 통과한다(JwtTokenFilter는 막지 않고 인증 필요 경로만 401)")
    void prometheus_invalidToken_allowed() throws Exception {
        mockMvc.perform(get(PROMETHEUS_URL).header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("회귀: 다른 사용자 API는 토큰 없이 401")
    void userApi_anonymous_unauthorized() throws Exception {
        mockMvc.perform(get(USER_API_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("회귀: 내부 API는 내부 키 없이 401(내부 체인 그대로)")
    void internalApi_withoutKey_unauthorized() throws Exception {
        mockMvc.perform(get(INTERNAL_API_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("허용 목록에 없는 actuator 경로(/actuator/env)는 토큰 없이 401(노출 설정이 넓어져도 무인증으로 열리지 않는다)")
    void otherActuator_anonymous_unauthorized() throws Exception {
        mockMvc.perform(get(ENV_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("actuator는 GET만 허용한다: POST /actuator/prometheus는 토큰 없이 401")
    void prometheus_post_unauthorized() throws Exception {
        mockMvc.perform(post(PROMETHEUS_URL))
                .andExpect(status().isUnauthorized());
    }
}
