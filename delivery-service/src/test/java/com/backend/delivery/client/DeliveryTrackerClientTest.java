package com.backend.delivery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryTrackerBadRequestException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerQuotaExceededException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerServiceDownException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnauthenticatedException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnavailableException;
import com.backend.boundedContext.delivery.out.TrackResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery Tracker GraphQL 클라이언트. 실제 API 대신 MockRestServiceServer로 응답을 흉내 낸다 (MemberApiClientTest 방식)
 */
class DeliveryTrackerClientTest {

    private static final String API_URL = "https://tracker.test/graphql";
    private static final String CALLBACK_TOKEN = "test-callback-token-0123456789abcdef";

    private MockRestServiceServer server;
    private DeliveryTrackerClient client;

    @BeforeEach
    void setUp() {
        DeliveryTrackerPolicy policy = new DeliveryTrackerPolicy(API_URL, "id", "secret",
                "http://localhost:8085/webhooks/delivery-tracker", CALLBACK_TOKEN, 48, 24, 15000, 5, 900, true, true);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // 호출 제한 대기로 테스트가 느려지지 않게 넉넉한 제한기를 넘긴다
        client = new DeliveryTrackerClient(builder, policy, new CallRateLimiter(1000), JsonMapper.builder().build());
    }

    @Test
    @DisplayName("track: 헤더 TRACKQL-API-KEY id:secret, 본문 query·variables로 보내고 마지막 상태·시각을 돌려준다")
    void track_delivered() {
        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "TRACKQL-API-KEY id:secret"))
                .andExpect(jsonPath("$.query").value(Matchers.containsString("track(carrierId: $carrierId")))
                .andExpect(jsonPath("$.variables.carrierId").value("kr.cjlogistics"))
                .andExpect(jsonPath("$.variables.trackingNumber").value("123456789012"))
                .andRespond(withSuccess("""
                        {"data": {"track": {"lastEvent": {"time": "2026-11-05T01:00:00Z", "status": {"code": "DELIVERED"}}}}}
                        """, MediaType.APPLICATION_JSON));

        TrackResult result = client.track("kr.cjlogistics", "123456789012");

        server.verify();
        assertThat(result.found()).isTrue();
        assertThat(result.delivered()).isTrue();
        assertThat(result.eventTime()).isEqualTo(OffsetDateTime.of(2026, 11, 5, 1, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("track: 이벤트가 아직 없으면 found=true, 상태 null")
    void track_noEvent() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("""
                        {"data": {"track": {"lastEvent": null}}}
                        """, MediaType.APPLICATION_JSON));

        TrackResult result = client.track("kr.cjlogistics", "123456789012");

        assertThat(result.found()).isTrue();
        assertThat(result.statusCode()).isNull();
    }

    @Test
    @DisplayName("HTTP 200 + errors[].extensions.code = UNAUTHENTICATED → 인증 실패 예외")
    void unauthenticated() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(error("UNAUTHENTICATED"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerUnauthenticatedException.class);
    }

    @Test
    @DisplayName("HTTP 200 + NOT_FOUND → notFound()")
    void notFound() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(error("NOT_FOUND"), MediaType.APPLICATION_JSON));

        assertThat(client.track("kr.cjlogistics", "123456789012")).isEqualTo(TrackResult.notFound());
    }

    @Test
    @DisplayName("HTTP 429 또는 할당량 오류 코드 → 할당량 초과 예외")
    void quotaExceeded() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("Too Many Requests"));
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(error(DeliveryTrackerClient.QUOTA_EXCEEDED), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerQuotaExceededException.class);
        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerQuotaExceededException.class);
    }

    @Test
    @DisplayName("5xx·접속 실패·시간 초과 → 서비스 장애 예외(회차 중단 대상)")
    void unavailable() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>Bad Gateway</html>"));
        server.expect(requestTo(API_URL))
                .andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(API_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                    .isInstanceOf(DeliveryTrackerServiceDownException.class);
        }
    }

    @Test
    @DisplayName("HTTP 200 안의 그 밖의 GraphQL 오류 코드 → 건별 호출 실패(Unavailable, 서비스 장애 아님). 5xx 안의 코드는 서비스 장애")
    void otherGraphQlError() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(error("INTERNAL"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(error("NOT_FOUND"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(error("INTERNAL")));

        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isExactlyInstanceOf(DeliveryTrackerUnavailableException.class);
        // 웹훅 등록의 NOT_FOUND도 건별 오류다(track의 NOT_FOUND만 notFound()로 바뀐다, 결정 D8)
        assertThatThrownBy(() -> client.registerWebhook("kr.epost", "6012345678901", "https://x/t", OffsetDateTime.now()))
                .isExactlyInstanceOf(DeliveryTrackerUnavailableException.class)
                .hasMessageContaining("code=NOT_FOUND");
        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerServiceDownException.class);
        server.verify();
    }

    @Test
    @DisplayName("오류 메시지에 client secret·콜백 토큰이 섞여 와도 예외 메시지에서 가린다")
    void secretsMasked() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("""
                        {"data": null, "errors": [{"message": "bad key secret / %s", "extensions": {"code": "BAD_REQUEST"}}]}
                        """.formatted(CALLBACK_TOKEN), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerBadRequestException.class)
                .hasMessageContaining("bad key ***")
                .hasMessageNotContaining(CALLBACK_TOKEN);
    }

    @Test
    @DisplayName("registerWebhook: input에 송장·콜백 URL·만료 시각(ISO8601)을 담고, 결과 true/false를 돌려준다")
    void registerWebhook() {
        OffsetDateTime expiration = OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.ofHours(9));
        String callbackUrl = "http://localhost:8085/webhooks/delivery-tracker/" + CALLBACK_TOKEN;
        server.expect(requestTo(API_URL))
                .andExpect(header("Authorization", "TRACKQL-API-KEY id:secret"))
                .andExpect(jsonPath("$.query").value(Matchers.containsString("registerTrackWebhook(input: $input)")))
                .andExpect(jsonPath("$.variables.input.carrierId").value("kr.epost"))
                .andExpect(jsonPath("$.variables.input.trackingNumber").value("6012345678901"))
                .andExpect(jsonPath("$.variables.input.callbackUrl").value(callbackUrl))
                .andExpect(jsonPath("$.variables.input.expirationTime").value("2026-11-10T09:00:00+09:00"))
                .andRespond(withSuccess("""
                        {"data": {"registerTrackWebhook": true}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("""
                        {"data": {"registerTrackWebhook": false}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.registerWebhook("kr.epost", "6012345678901", callbackUrl, expiration)).isTrue();
        assertThat(client.registerWebhook("kr.epost", "6012345678901", callbackUrl, expiration)).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("registerWebhook: UNAUTHENTICATED면 false가 아니라 인증 실패 예외")
    void registerWebhook_unauthenticated() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(error("UNAUTHENTICATED"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.registerWebhook("kr.epost", "6012345678901", "http://x/t", OffsetDateTime.now()))
                .isInstanceOf(DeliveryTrackerUnauthenticatedException.class);
    }

    @Test
    @DisplayName("BAD_REQUEST → 요청 거부 예외(호출 실패와 구분). 메시지는 담고 콜백 토큰은 가린다")
    void badRequest() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("""
                        {"data": null, "errors": [{"message": "The callbackUrl argument only accepts https protocol.",
                          "extensions": {"code": "BAD_REQUEST"}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("""
                        {"data": null, "errors": [{"message": "invalid callbackUrl http://x/%s",
                          "extensions": {"code": "BAD_REQUEST"}}]}
                        """.formatted(CALLBACK_TOKEN), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(error("BAD_REQUEST"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.registerWebhook("kr.epost", "6012345678901", "http://x/t", OffsetDateTime.now()))
                .isInstanceOf(DeliveryTrackerBadRequestException.class)
                .hasMessageContaining("only accepts https protocol");
        assertThatThrownBy(() -> client.registerWebhook("kr.epost", "6012345678901", "http://x/t", OffsetDateTime.now()))
                .isInstanceOf(DeliveryTrackerBadRequestException.class)
                .hasMessageNotContaining(CALLBACK_TOKEN);
        assertThatThrownBy(() -> client.track("kr.cjlogistics", "123456789012"))
                .isInstanceOf(DeliveryTrackerBadRequestException.class);
        server.verify();
    }

    private static String error(String code) {
        return """
                {"data": null, "errors": [{"message": "error", "extensions": {"code": "%s"}}]}
                """.formatted(code);
    }
}
