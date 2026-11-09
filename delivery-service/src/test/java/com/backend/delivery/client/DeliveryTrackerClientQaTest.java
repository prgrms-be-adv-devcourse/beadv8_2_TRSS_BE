package com.backend.delivery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryTrackerBadRequestException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerQuotaExceededException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnauthenticatedException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnavailableException;
import com.backend.boundedContext.delivery.out.TrackResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * [QA] Delivery Tracker 클라이언트: 예외 메시지에 비밀값이 없는지, 분류 경계(HTTP 401·할당량 코드·응답 깨짐), registerWebhook의 NOT_FOUND 분류
 */
class DeliveryTrackerClientQaTest {

    private static final String API_URL = "https://tracker.test/graphql";
    private static final String CALLBACK_TOKEN = "qa-callback-token-abcdefghijklmnopqrstuvwxyz0123";
    private static final String CALLBACK_BASE = "https://palette.test/webhooks/delivery-tracker";
    private static final String CLIENT_SECRET = "qa-client-secret-value";

    private MockRestServiceServer server;
    private DeliveryTrackerClient client;

    @BeforeEach
    void setUp() {
        DeliveryTrackerPolicy policy = new DeliveryTrackerPolicy(API_URL, "qa-id", CLIENT_SECRET,
                CALLBACK_BASE, CALLBACK_TOKEN, 48, 24, 15000, 5, 900, true, false);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeliveryTrackerClient(builder, policy, new CallRateLimiter(1000), JsonMapper.builder().build());
    }

    private void register() {
        client.registerWebhook("kr.cjlogistics", "123456789012", CALLBACK_BASE + "/" + CALLBACK_TOKEN,
                OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("[QA] 웹훅 등록 요청 본문에는 콜백 URL(토큰 포함)이 https로 들어간다")
    void register_callbackUrlHttps() {
        server.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.variables.input.callbackUrl").value(CALLBACK_BASE + "/" + CALLBACK_TOKEN))
                .andExpect(jsonPath("$.variables.input.expirationTime").value("2026-11-10T09:00:00+09:00"))
                .andRespond(withSuccess("{\"data\":{\"registerTrackWebhook\":true}}", MediaType.APPLICATION_JSON));

        register();
        server.verify();
    }

    @Test
    @DisplayName("[QA] 모든 실패 예외 메시지에 콜백 토큰·client secret이 없고, BAD_REQUEST 외에는 콜백 주소도 없다")
    void exceptionMessages_noSecrets() {
        // 실제 BAD_REQUEST 응답처럼 외부 메시지에 콜백 URL(토큰 포함)이 되돌아오는 경우
        String echoed = "bad callbackUrl " + CALLBACK_BASE + "/" + CALLBACK_TOKEN;
        server.expect(requestTo(API_URL)).andRespond(withSuccess("""
                {"errors":[{"message":"%s","extensions":{"code":"BAD_REQUEST"}}]}""".formatted(echoed), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withSuccess("""
                {"errors":[{"message":"%s","extensions":{"code":"INTERNAL"}}]}""".formatted(echoed), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withSuccess("""
                {"errors":[{"message":"%s","extensions":{"code":"UNAUTHENTICATED"}}]}""".formatted(echoed), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withException(new ConnectException("Connection refused")));

        for (int i = 0; i < 4; i++) {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(this::register);
            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage()).doesNotContain(CALLBACK_TOKEN).doesNotContain(CLIENT_SECRET);
            if (!(thrown instanceof DeliveryTrackerBadRequestException)) {
                // BAD_REQUEST만 외부 메시지를 담는다(가림 처리). 나머지는 고정 문구 + 코드
                assertThat(thrown.getMessage()).doesNotContain(CALLBACK_BASE);
            }
        }
    }

    @Test
    @DisplayName("[QA] BAD_REQUEST 메시지는 200자에서 자른다")
    void badRequest_truncated() {
        String longMessage = "x".repeat(500);
        server.expect(requestTo(API_URL)).andRespond(withSuccess("""
                {"errors":[{"message":"%s","extensions":{"code":"BAD_REQUEST"}}]}""".formatted(longMessage), MediaType.APPLICATION_JSON));

        assertThatThrownBy(this::register)
                .isInstanceOf(DeliveryTrackerBadRequestException.class)
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(300));
    }

    @Test
    @DisplayName("[QA] HTTP 401(본문 없음) → 인증 실패, 할당량 코드(HTTP 200) → 할당량 초과")
    void http401_andQuotaCode() {
        server.expect(requestTo(API_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(API_URL)).andRespond(withSuccess("""
                {"errors":[{"message":"quota","extensions":{"code":"QUOTA_EXCEEDED"}}]}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerUnauthenticatedException.class);
        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerQuotaExceededException.class);
    }

    @Test
    @DisplayName("[QA] HTTP 200인데 JSON이 아님·data 없음 → track은 호출 실패 또는 notFound, registerWebhook은 호출 실패/false")
    void brokenBody() {
        server.expect(requestTo(API_URL)).andRespond(withSuccess("<html>ok</html>", MediaType.TEXT_HTML));
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"data\":null}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerUnavailableException.class);
        TrackResult nullData = client.track("kr.cjlogistics", "123456789012");
        assertThat(nullData.delivered()).isFalse();
        // registerTrackWebhook 필드가 없으면 false (WARN 후 다음 회차)
        assertThat(client.registerWebhook("kr.cjlogistics", "123456789012", CALLBACK_BASE + "/" + CALLBACK_TOKEN,
                OffsetDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("[QA] registerWebhook이 NOT_FOUND를 받으면 지금은 호출 실패(Unavailable)로 분류된다 → 스케줄러가 회차를 멈춘다 (결정 D8 콘솔 확인 필요)")
    void register_notFound_isUnavailable() {
        server.expect(requestTo(API_URL)).andRespond(withSuccess("""
                {"errors":[{"message":"tracking not found","extensions":{"code":"NOT_FOUND"}}]}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(this::register).isInstanceOf(DeliveryTrackerUnavailableException.class)
                .hasMessageContaining("code=NOT_FOUND");
    }
}
