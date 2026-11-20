package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 서비스에서 응답을 못 받을 때: 502(연결 실패)·504(시간 초과)로 답하고, 요청 주소 없이 WARN으로 남는지(ERROR 알림이 안 가는지) 확인한다.
 * delivery·market은 아무도 듣지 않는 포트(1)로 연결 실패를, payment는 늦게 답하는 가짜 서비스로 시간 초과를 만든다(응답 대기 1초).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DELIVERY_SERVICE_URL=http://localhost:1",
        "MARKET_SERVICE_URL=http://localhost:1",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s",
        "logging.level.com.backend.gateway.ServiceConnectionFailureFilter=DEBUG"
})
class ServiceConnectionFailureFilterTest {

    private static final String WEBHOOK_TOKEN = "secret-webhook-token-7f3a9c";

    // 응답 대기(1초)보다 늦게 답하는 가짜 payment
    private static final DisposableServer slowPayment = HttpServer.create()
            .port(0)
            .handle((request, response) -> response.sendString(Mono.just("late").delayElement(Duration.ofSeconds(3))))
            .bindNow();

    // 응답을 조금 보내다가 연결을 끊는 가짜 notification (알림 구독이 도중에 끊기는 경우)
    private static final DisposableServer cutNotification = HttpServer.create()
            .port(0)
            .handle((request, response) -> response.sendString(Flux.just("data: part\n\n")
                    .concatWith(Mono.delay(Duration.ofMillis(500)).then(Mono.error(new IllegalStateException("cut"))))))
            .bindNow();

    @DynamicPropertySource
    static void routeToFakeServices(DynamicPropertyRegistry registry) {
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + slowPayment.port());
        registry.add("NOTIFICATION_SERVICE_URL", () -> "http://localhost:" + cutNotification.port());
    }

    @AfterAll
    static void stopFakeServices() {
        slowPayment.disposeNow();
        cutNotification.disposeNow();
    }

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void 배송_웹훅을_못_넘기면_502이고_로그에_토큰이_없다(CapturedOutput output) {
        client().post().uri("/webhooks/delivery-tracker/" + WEBHOOK_TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(output).contains("서비스에 연결하지 못했습니다 (route=delivery");
        assertThat(output).doesNotContain(WEBHOOK_TOKEN);
    }

    @Test
    void 서비스가_꺼져_있으면_502이고_주소가_든_ERROR를_남기지_않는다(CapturedOutput output) {
        client().get().uri("/api/v1/products")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(output).contains("WARN").contains("서비스에 연결하지 못했습니다 (route=market");
        // Spring 기본 오류 처리기의 로그("500 Server Error for HTTP GET \"/api/v1/products\"")가 없어야 Slack ERROR 알림이 안 간다
        assertThat(output).doesNotContain("Server Error for HTTP");
    }

    // 그냥 두면 504만 나가고 로그가 하나도 안 남아, 서비스가 멈춰도 알 수 없다
    @Test
    void 서비스가_응답_시간을_넘기면_504이고_WARN을_남긴다(CapturedOutput output) {
        client().get().uri("/v3/api-docs")   // 로그인 없이 payment로 가는 경로
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

        assertThat(output).contains("서비스 응답 시간 초과 (route=payment");
    }

    // 서비스가 응답을 시작한 뒤 끊긴 건 "응답 실패"가 아니다. 알림용 WARN을 남기면 알림 구독이 끊길 때마다 Slack이 울린다
    @Test
    void 응답을_보내던_중_끊기면_알림용_WARN을_남기지_않는다(CapturedOutput output) {
        client().get().uri("/v3/api-docs/notification")   // 로그인 없이 notification으로 가는 경로
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody()
                .onErrorComplete()
                .blockLast(Duration.ofSeconds(5));

        assertThat(output).contains("응답을 보내던 중 연결이 끊겼습니다 (route=notification");
        assertThat(output).doesNotContain("서비스에 연결하지 못했습니다 (route=notification");
    }
}
