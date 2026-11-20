package com.backend.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;

/**
 * [QA] 컨테이너와 같은 구성(관리 포트를 따로 띄움, Dockerfile의 MANAGEMENT_SERVER_PORT)을 실제 Tomcat으로 띄워 본다.
 * 경로 규칙만 보는 ActuatorAccessTest와 달리 실제 actuator·Prometheus 레지스트리·기본값 파일·SecurityConfig가 함께 동작한다.
 * - 관리 포트: health·prometheus만 200(토큰 없이, 잘못된 토큰이어도), 나머지 엔드포인트는 토큰 없이 401
 *   (SecurityConfig가 GET health·prometheus만 허용해, 노출되지 않은 경로도 인증 단계에서 먼저 막는다)
 * - 앱 포트: actuator 없음(health·prometheus 404, 그 밖의 /actuator 경로는 인증 단계에서 401), 일반 API 인증은 그대로(401)
 * - 지표: application 태그, JVM 지표, http.server.requests SLO 버킷
 */
@SpringBootTest(
        classes = ActuatorManagementPortQaTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "spring.application.name=qa-service",
                "custom.jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWtleS1hdC1sZWFzdC0zMi1ieXRlcyEh",
                "custom.jwt.accessTokenExpireMinutes=30",
                "custom.internal.apiKey=test-internal-key",
                "custom.cors.allowedOrigins=http://localhost:3000"
        })
class ActuatorManagementPortQaTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int appPort;

    @LocalManagementPort
    private int managementPort;

    // DB·JPA 없이 띄운다(common에는 DB 드라이버가 없다). 나머지 자동 설정은 서비스와 같다
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class})
    @Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
            JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class, QaController.class})
    static class TestApplication {
    }

    @RestController
    static class QaController {

        // SecurityConfig의 공개 GET 경로(/api/v1/products/**)
        @GetMapping("/api/v1/products/qa-ping")
        String publicPing() {
            return "ok";
        }

        @GetMapping("/api/v1/orders/qa-ping")
        String protectedPing() {
            return "ok";
        }
    }

    private static HttpResponse<String> get(int port, String path, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("관리 포트: 토큰 없이 /actuator/prometheus 200, application 태그와 JVM 지표가 있다")
    void managementPort_prometheus_anonymous() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes")
                .contains("application=\"qa-service\"");
    }

    @Test
    @DisplayName("관리 포트: 앱 요청 뒤 http_server_requests SLO 버킷(le=0.1 등 6개 경계)이 나온다")
    void managementPort_httpServerRequestsSloBuckets() throws Exception {
        assertThat(get(appPort, "/api/v1/products/qa-ping", null).statusCode()).isEqualTo(200);

        String body = get(managementPort, "/actuator/prometheus", null).body();

        assertThat(body.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_bucket{"))
                .filter(line -> line.contains("uri=\"/api/v1/products/qa-ping\""))
                .toList())
                .anyMatch(line -> line.contains("le=\"0.05\""))
                .anyMatch(line -> line.contains("le=\"0.1\""))
                .anyMatch(line -> line.contains("le=\"0.3\""))
                .anyMatch(line -> line.contains("le=\"0.5\""))
                .anyMatch(line -> line.contains("le=\"1.0\""))
                .anyMatch(line -> line.contains("le=\"3.0\""))
                .allMatch(line -> line.contains("application=\"qa-service\""));
    }

    @Test
    @DisplayName("관리 포트: 토큰 없이 /actuator/health 200")
    void managementPort_health_anonymous() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("관리 포트: 잘못된 Bearer 토큰이 붙어도 /actuator/prometheus 200")
    void managementPort_invalidToken_allowed() throws Exception {
        assertThat(get(managementPort, "/actuator/prometheus", "Bearer invalid.token.value").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "관리 포트: {0} 는 토큰 없이 닿지 않는다(401)")
    @ValueSource(strings = {"/actuator/env", "/actuator/heapdump", "/actuator/threaddump", "/actuator/beans",
            "/actuator/configprops", "/actuator/mappings", "/actuator/loggers", "/actuator/metrics",
            "/actuator/shutdown", "/actuator/info", "/actuator/scheduledtasks", "/actuator/conditions"})
    void managementPort_otherEndpoints_notExposed(String path) throws Exception {
        assertThat(get(managementPort, path, null).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest(name = "앱 포트: {0} 에는 actuator가 없다(404)")
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/health"})
    void appPort_noActuator(String path) throws Exception {
        assertThat(get(appPort, path, null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("앱 포트: 허용 목록 밖의 /actuator 경로는 토큰 없이 401")
    void appPort_actuatorRoot_unauthorized() throws Exception {
        assertThat(get(appPort, "/actuator", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("회귀(앱 포트): 인증이 필요한 API는 토큰 없이 401, 잘못된 토큰이어도 401")
    void appPort_protectedApi_unauthorized() throws Exception {
        assertThat(get(appPort, "/api/v1/orders/qa-ping", null).statusCode()).isEqualTo(401);
        assertThat(get(appPort, "/api/v1/orders/qa-ping", "Bearer invalid.token.value").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("회귀(앱 포트): 내부 API는 키 없이 401")
    void appPort_internalApi_withoutKey_unauthorized() throws Exception {
        assertThat(get(appPort, "/internal/v1/qa-ping", null).statusCode()).isEqualTo(401);
    }
}
