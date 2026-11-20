package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * gateway JWT 검사. 서비스 7개 대신 가짜 서비스 서버 하나로 모든 경로를 받아,
 * 통과한 요청은 서비스까지 가고 막힌 요청은 서비스에 닿지 않는지 확인한다.
 */
// 실제 포트로 띄워 보낸다: CORS·헤더 처리가 운영과 같은 경로(Netty 서버)를 타게
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayJwtFilterTest {

    // nginx가 넣어 보내는 값. 실제 요청에는 Host가 항상 있다
    private static final String PUBLIC_HOST = "palette-shop.example";

    // 로컬 프론트 주소. CORS_ALLOWED_ORIGINS 기본값에 들어 있다
    private static final String FRONT_ORIGIN = "http://localhost:5173";

    // 가짜 서비스: 받은 요청 수와 헤더를 기록하고 200을 돌려준다. 진짜 서비스처럼 Origin이 오면 CORS 허용 헤더를 붙인다
    private static final AtomicInteger serviceHits = new AtomicInteger();
    private static final AtomicReference<io.netty.handler.codec.http.HttpHeaders> receivedHeaders = new AtomicReference<>();
    private static final DisposableServer fakeService = HttpServer.create()
            .port(0)
            .handle((request, response) -> {
                serviceHits.incrementAndGet();
                receivedHeaders.set(request.requestHeaders().copy());
                String origin = request.requestHeaders().get(HttpHeaders.ORIGIN);
                if (origin != null) {
                    response.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                }
                return response.sendString(Mono.just("ok"));
            })
            .bindNow();

    @DynamicPropertySource
    static void routeAllServicesToFakeService(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + fakeService.port();
        for (String name : new String[]{"MEMBER", "MARKET", "PAYMENT", "SETTLEMENT", "DELIVERY", "NOTIFICATION", "AI"}) {
            registry.add(name + "_SERVICE_URL", () -> url);
        }
    }

    @AfterAll
    static void stopFakeService() {
        fakeService.disposeNow();
    }

    @Value("${local.server.port}")
    private int port;

    @Value("${custom.jwt.secret}")
    private String secret;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.HOST, PUBLIC_HOST)
                .build();
        serviceHits.set(0);
        receivedHeaders.set(null);
    }

    @Test
    void 공개_경로는_토큰_없이_서비스로_넘긴다() {
        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        client.post().uri("/webhooks/delivery-tracker/secret-token").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/influencers/3").exchange().expectStatus().isOk();

        assertThat(serviceHits).hasValue(4);
    }

    @Test
    void 공개_경로는_만료된_토큰이어도_서비스로_넘긴다() {
        client.get().uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void 로그인_필요_경로에_토큰이_없으면_401이고_서비스에_닿지_않는다() {
        expectUnauthorized(client.get().uri("/api/v1/orders").exchange(), "401-UNAUTHORIZED");
        // 공개는 GET만이라 같은 경로라도 POST는 로그인 필요
        expectUnauthorized(client.post().uri("/api/v1/products").exchange(), "401-UNAUTHORIZED");
        // 인플루언서 프로필(/influencers/*)은 공개지만 내 신청 정보(/influencers/me)는 로그인 필요
        expectUnauthorized(client.get().uri("/api/v1/influencers/me").exchange(), "401-UNAUTHORIZED");

        assertThat(serviceHits).hasValue(0);
    }

    @Test
    void 가짜_토큰이면_401_UNAUTHORIZED다() {
        SecretKey otherKey = Keys.hmacShaKeyFor("another-secret-key-another-secret-key!!".getBytes());
        String forged = Jwts.builder().subject("1").claim("role", "ADMIN").signWith(otherKey).compact();

        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(forged)).exchange(),
                "401-UNAUTHORIZED");
        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").exchange(),
                "401-UNAUTHORIZED");
        assertThat(serviceHits).hasValue(0);
    }

    @Test
    void 만료된_토큰이면_401_AUTH_TOKEN_EXPIRED다() {
        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken())).exchange(),
                "401-AUTH_TOKEN_EXPIRED");
        assertThat(serviceHits).hasValue(0);
    }

    @Test
    void 정상_토큰이면_토큰을_그대로_서비스로_넘긴다() {
        String token = Jwts.builder()
                .subject("1").claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key())
                .compact();

        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedHeaders.get().get(HttpHeaders.AUTHORIZATION)).isEqualTo(bearer(token));
    }

    // 서비스가 보는 도메인·사용자 IP·https 여부가 nginx만 있을 때와 같아야 한다
    @Test
    void nginx가_보낸_Host와_X_Forwarded_헤더를_그대로_넘긴다() {
        client.get().uri("/api/v1/products")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Forwarded-Proto", "https")
                .exchange()
                .expectStatus().isOk();

        var headers = receivedHeaders.get();
        assertThat(headers.get(HttpHeaders.HOST)).isEqualTo(PUBLIC_HOST);
        assertThat(headers.get("X-Forwarded-For")).isEqualTo("203.0.113.7");
        assertThat(headers.get("X-Forwarded-Proto")).isEqualTo("https");
    }

    @Test
    void CORS_확인_요청은_토큰_없이_넘긴다() {
        client.method(HttpMethod.OPTIONS).uri("/api/v1/orders").exchange().expectStatus().isOk();
        assertThat(serviceHits).hasValue(1);
    }

    // 이 헤더가 있어야 nginx가 알림을 모아 두지 않고 바로 브라우저로 흘려보낸다(nginx엔 구독 경로를 적지 않음)
    @Test
    void 알림_구독_응답에는_nginx_버퍼링을_끄는_헤더를_붙인다() {
        String token = Jwts.builder()
                .subject("1").claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key())
                .compact();

        client.get().uri("/api/v1/notifications/subscribe").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Accel-Buffering", "no");
    }

    // 다른 주소(로컬 프론트)에서 부를 때: 브라우저는 본 요청 전에 OPTIONS로 허용 여부를 묻는다
    @Test
    void CORS_사전_확인은_gateway가_허용_헤더로_답한다() {
        client.options().uri("/api/v1/orders")
                .header(HttpHeaders.ORIGIN, FRONT_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    // 허용 헤더가 없으면 브라우저가 401 내용을 못 읽어 프론트가 토큰 재발급을 못 한다
    @Test
    void gateway가_막은_401에도_CORS_허용_헤더가_붙는다() {
        client.get().uri("/api/v1/orders").header(HttpHeaders.ORIGIN, FRONT_ORIGIN)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT_ORIGIN);
    }

    // gateway와 서비스가 둘 다 붙이면 헤더가 두 개가 되고, 브라우저는 두 개면 거절한다
    @Test
    void 서비스_응답의_CORS_허용_헤더는_하나만_남는다() {
        client.get().uri("/api/v1/products").header(HttpHeaders.ORIGIN, FRONT_ORIGIN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().values(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        values -> assertThat(values).containsExactly(FRONT_ORIGIN));
    }

    // gateway에선 공개 경로(products/**)로 보이지만 서비스(Tomcat)가 풀면 /api/v1/orders 같은 다른 경로가 되는 주소
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/products/../orders",
            "/api/v1/products/%2e%2e/orders",
            "/api/v1/products/%2E%2E/orders",
            "/api/v1/products/..%2forders",
            "/api/v1/products/./orders",
            "/api/v1/products/.."
    })
    void 다른_경로로_풀리는_주소는_400이고_서비스에_닿지_않는다(String path) {
        // URI 객체로 넘겨 테스트 클라이언트가 ..를 미리 정리하지 않게 한다
        client.get().uri(URI.create("http://localhost:" + port + path))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.resultCode").isEqualTo("400-INVALID_REQUEST");

        assertThat(serviceHits).hasValue(0);
    }

    // ── 회원 정보 헤더(TRSS-296): 서비스(common)는 이 헤더로 회원 정보를 만든다 ──

    @Test
    void 정상_토큰이면_회원_id와_역할을_헤더로_넘긴다() {
        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token("37", "USER")))
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedHeaders.get().get("X-Member-Id")).isEqualTo("37");
        assertThat(receivedHeaders.get().get("X-Member-Role")).isEqualTo("USER");
    }

    // 밖에서 넣은 회원 헤더가 서비스에 닿으면 누구나 다른 회원·관리자 행세를 할 수 있다
    @Test
    void 밖에서_넣은_회원_헤더는_지우고_토큰의_회원으로_넘긴다() {
        client.get().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(token("37", "USER")))
                .header("X-Member-Id", "1")
                .header("X-Member-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedHeaders.get().getAll("X-Member-Id")).containsExactly("37");
        assertThat(receivedHeaders.get().getAll("X-Member-Role")).containsExactly("USER");
    }

    @Test
    void 공개_경로에_토큰_없이_회원_헤더만_넣으면_지우고_비회원으로_넘긴다() {
        client.get().uri("/api/v1/products")
                .header("X-Member-Id", "1")
                .header("X-Member-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedHeaders.get().contains("X-Member-Id")).isFalse();
        assertThat(receivedHeaders.get().contains("X-Member-Role")).isFalse();
    }

    // 상품 목록·맞춤 추천은 공개 경로지만 로그인했으면 그 회원 기준으로 답한다
    @Test
    void 공개_경로라도_정상_토큰이면_회원_헤더를_넘긴다() {
        client.get().uri("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(token("37", "USER")))
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedHeaders.get().get("X-Member-Id")).isEqualTo("37");
    }

    @Test
    void 공개_경로에_만료된_토큰이면_헤더_없이_비회원으로_넘긴다() {
        client.get().uri("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken()))
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedHeaders.get().contains("X-Member-Id")).isFalse();
    }

    @Test
    void 회원_id가_숫자가_아니거나_역할이_없는_토큰은_가짜로_본다() {
        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token("abc", "USER"))).exchange(),
                "401-UNAUTHORIZED");
        expectUnauthorized(client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token("37", "SUPER"))).exchange(),
                "401-UNAUTHORIZED");
        assertThat(serviceHits).hasValue(0);
    }

    // ── 경로별 역할(TRSS-296): common SecurityConfig와 같은 규칙. 안 맞으면 서비스에 닿지 않고 403 ──

    @ParameterizedTest(name = "{0} {1} 역할 {2} → 403")
    @CsvSource({
            "GET, /api/v1/admin/payments, USER",            // 관리자 전용
            "GET, /api/v1/admin/payments, LOGISTICS",
            "GET, /api/v1/logistics/deliveries, USER",      // 물류 담당자 전용
            "GET, /api/v1/logistics/deliveries, ADMIN",
            "GET, /api/v1/orders, ADMIN",                   // 구매 기능: 관리자·물류 담당자는 막음
            "GET, /api/v1/seller/products, LOGISTICS",      // 판매자 기능
            "GET, /api/v1/influencer/partnerships, ADMIN",  // 인플루언서 기능
            "POST, /api/v1/users/me/withdrawal, ADMIN",     // 탈퇴 조건 조회
            "DELETE, /api/v1/users/me, ADMIN",              // 회원 탈퇴
    })
    void 역할이_맞지_않으면_403이고_서비스에_닿지_않는다(String method, String path, String role) {
        client.method(HttpMethod.valueOf(method)).uri(path).header(HttpHeaders.AUTHORIZATION, bearer(token("1", role)))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.resultCode").isEqualTo("403-FORBIDDEN");

        assertThat(serviceHits).hasValue(0);
    }

    @ParameterizedTest(name = "{0} {1} 역할 {2} → 통과")
    @CsvSource({
            "GET, /api/v1/admin/payments, ADMIN",
            "GET, /api/v1/logistics/deliveries, LOGISTICS",
            "GET, /api/v1/orders, USER",
            "GET, /api/v1/seller/products, USER",
            "GET, /api/v1/users/me, ADMIN",                 // 내 정보 조회는 역할을 가리지 않는다
            "GET, /api/v1/notifications, LOGISTICS",        // 목록에 없는 경로는 로그인만 하면 된다
    })
    void 역할이_맞으면_서비스로_넘긴다(String method, String path, String role) {
        client.method(HttpMethod.valueOf(method)).uri(path).header(HttpHeaders.AUTHORIZATION, bearer(token("1", role)))
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedHeaders.get().get("X-Member-Role")).isEqualTo(role);
    }

    private String token(String memberId, String role) {
        return Jwts.builder()
                .subject(memberId).claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key())
                .compact();
    }

    private void expectUnauthorized(WebTestClient.ResponseSpec response, String resultCode) {
        response.expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.resultCode").isEqualTo(resultCode)
                .jsonPath("$.msg").isNotEmpty();
    }

    private String expiredToken() {
        return Jwts.builder()
                .subject("1").claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key())
                .compact();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
