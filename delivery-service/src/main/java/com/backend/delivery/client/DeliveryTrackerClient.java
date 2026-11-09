package com.backend.delivery.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryTracker;
import com.backend.boundedContext.delivery.out.DeliveryTrackerBadRequestException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerQuotaExceededException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerServiceDownException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnauthenticatedException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnavailableException;
import com.backend.boundedContext.delivery.out.TrackResult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link DeliveryTracker}를 Delivery Tracker GraphQL API(POST {apiUrl})로 호출하는 구현.
 *
 * - 인증: Authorization: TRACKQL-API-KEY {clientId}:{clientSecret}
 * - 모든 호출 전에 호출 제한기(초당 maxCallsPerSecond회)를 거친다
 * - 결과는 HTTP 상태가 아니라 응답의 errors[0].extensions.code로 판단한다. HTTP 200이어도 errors가 있으면 실패다
 *   UNAUTHENTICATED → 인증 실패, NOT_FOUND(track) → 송장 없음, 할당량 코드·HTTP 429 → 할당량 초과,
 *   BAD_REQUEST → 요청 거부(재시도해도 실패, 그 건만 건너뜀), 그 밖의 코드 → 건별 호출 실패(Unavailable, 그 건만 실패)
 *   접속 실패·시간 초과·HTTP 5xx·해석할 수 없는 응답 → 서비스 장애(ServiceDown, 회차 중단)
 * - 예외 메시지에 콜백 토큰·client secret이 들어가지 않게 가린다
 */
@Component
public class DeliveryTrackerClient implements DeliveryTracker {

    static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    static final String NOT_FOUND = "NOT_FOUND";
    // 요청 값이 틀림(예: http 콜백 주소 "The callbackUrl argument only accepts https protocol."). 11-08 실제 응답으로 확인
    static final String BAD_REQUEST = "BAD_REQUEST";
    // 오류 메시지를 예외·로그에 담을 때 최대 길이
    private static final int MAX_ERROR_MESSAGE_LENGTH = 200;
    // TODO(배포 전 콘솔 확인): 할당량 초과 때 오는 errors[].extensions.code 값. 공개 문서에 없어 추정 값을 두고 HTTP 429도 함께 본다
    static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final long CONNECT_TIMEOUT_MILLIS = 3_000;

    static final String TRACK_QUERY = """
            query Track($carrierId: ID!, $trackingNumber: String!) {
              track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
                lastEvent {
                  time
                  status { code }
                }
              }
            }""";

    static final String REGISTER_WEBHOOK_MUTATION = """
            mutation RegisterTrackWebhook($input: RegisterTrackWebhookInput!) {
              registerTrackWebhook(input: $input)
            }""";

    private final RestClient restClient;
    private final String apiUrl;
    private final CallRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    // 오류 메시지에 콜백 URL·인증 값이 섞여 와도 로그에 남지 않게 가릴 값들
    private final String callbackToken;
    private final String clientSecret;

    @Autowired
    public DeliveryTrackerClient(DeliveryTrackerPolicy policy, ObjectMapper objectMapper) {
        this(RestClient.builder().requestFactory(requestFactory(policy.getReadTimeoutMillis())),
                policy, new CallRateLimiter(policy.getMaxCallsPerSecond()), objectMapper);
    }

    /** 테스트에서 MockRestServiceServer를 연결한 builder와 호출 제한기를 넘길 수 있게 열어 둔다 */
    DeliveryTrackerClient(RestClient.Builder builder, DeliveryTrackerPolicy policy,
                          CallRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.apiUrl = policy.getApiUrl();
        this.restClient = builder
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "TRACKQL-API-KEY " + policy.getClientId() + ":" + policy.getClientSecret())
                .build();
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.callbackToken = policy.getCallbackToken();
        this.clientSecret = policy.getClientSecret();
    }

    @Override
    public TrackResult track(String carrierId, String trackingNumber) {
        GraphQlResponse response = post(TRACK_QUERY, Map.of("carrierId", carrierId, "trackingNumber", trackingNumber));
        String errorCode = response.errorCode();
        if (NOT_FOUND.equals(errorCode)) {
            return TrackResult.notFound();
        }
        response.throwIfFailed();

        JsonNode track = response.data().path("track");
        if (track.isMissingNode() || track.isNull()) {
            return TrackResult.notFound();
        }
        JsonNode lastEvent = track.path("lastEvent");
        if (lastEvent.isMissingNode() || lastEvent.isNull()) {
            return TrackResult.of(null, null);   // 송장은 있지만 아직 이벤트가 없음
        }
        return TrackResult.of(text(lastEvent.path("status").path("code")), time(lastEvent.path("time")));
    }

    @Override
    public boolean registerWebhook(String carrierId, String trackingNumber, String callbackUrl, OffsetDateTime expirationTime) {
        Map<String, Object> input = Map.of(
                "carrierId", carrierId,
                "trackingNumber", trackingNumber,
                "callbackUrl", callbackUrl,
                "expirationTime", expirationTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        GraphQlResponse response = post(REGISTER_WEBHOOK_MUTATION, Map.of("input", input));
        response.throwIfFailed();
        return response.data().path("registerTrackWebhook").asBoolean(false);
    }

    /** GraphQL 요청을 보내고 응답을 읽는다. 응답을 받지 못하면 Unavailable */
    private GraphQlResponse post(String query, Map<String, Object> variables) {
        rateLimiter.acquire();
        try {
            return restClient.post()
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)))
                    .exchange((request, response) -> read(response.getStatusCode().value(),
                            StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw new DeliveryTrackerServiceDownException("Delivery Tracker에 접속하지 못했습니다: " + mask(e.getMessage()), e);
        } catch (RestClientException e) {
            throw new DeliveryTrackerServiceDownException("Delivery Tracker 응답을 처리하지 못했습니다: " + mask(e.getMessage()), e);
        }
    }

    private GraphQlResponse read(int status, String body) throws IOException {
        JsonNode root = null;
        try {
            root = body == null || body.isBlank() ? null : objectMapper.readTree(body);
        } catch (JacksonException ignored) {
            // JSON이 아니면(예: 장애로 HTML이 옴) 상태 코드로만 판단한다
        }
        return new GraphQlResponse(status, root, this::mask);
    }

    /** 콜백 토큰·client secret을 ***로 가린다 */
    private String mask(String text) {
        if (text == null) return null;
        String masked = text;
        for (String secret : new String[]{callbackToken, clientSecret}) {
            if (secret != null && !secret.isEmpty()) {
                masked = masked.replace(secret, "***");
            }
        }
        return masked;
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static OffsetDateTime time(JsonNode node) {
        String value = text(node);
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;   // 시각을 읽지 못하면 확인 시각을 쓴다(DeliveryCheckTrackingUseCase)
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(int readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }

    /** GraphQL 응답. root가 null이면 JSON 본문이 없었다 */
    private record GraphQlResponse(int status, JsonNode root, UnaryOperator<String> masker) {

        JsonNode data() {
            return root == null ? tools.jackson.databind.node.MissingNode.getInstance() : root.path("data");
        }

        /** errors[0].extensions.code. 없으면 null */
        String errorCode() {
            if (root == null) return null;
            JsonNode errors = root.path("errors");
            if (!errors.isArray() || errors.isEmpty()) return null;
            JsonNode code = errors.get(0).path("extensions").path("code");
            return code.isMissingNode() || code.isNull() ? "" : code.asString();
        }

        /** errors[0].message. 콜백 토큰·client secret은 가리고 길이를 자른다 */
        String errorMessage() {
            JsonNode message = root == null ? null : root.path("errors").path(0).path("message");
            if (message == null || message.isMissingNode() || message.isNull()) return "(메시지 없음)";
            String text = masker.apply(message.asString());
            return text.length() > MAX_ERROR_MESSAGE_LENGTH ? text.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : text;
        }

        /** 오류 응답이면 종류에 맞는 예외를 던진다 */
        void throwIfFailed() {
            String code = errorCode();
            if (UNAUTHENTICATED.equals(code)) {
                throw new DeliveryTrackerUnauthenticatedException("Delivery Tracker 인증에 실패했습니다(UNAUTHENTICATED). HTTP " + status);
            }
            if (QUOTA_EXCEEDED.equals(code) || status == HTTP_TOO_MANY_REQUESTS) {
                throw new DeliveryTrackerQuotaExceededException("Delivery Tracker 할당량을 넘었습니다. HTTP " + status + ", code=" + code);
            }
            if (BAD_REQUEST.equals(code)) {
                throw new DeliveryTrackerBadRequestException("Delivery Tracker가 요청을 거부했습니다(BAD_REQUEST): " + errorMessage());
            }
            if (status == 401) {
                throw new DeliveryTrackerUnauthenticatedException("Delivery Tracker 인증에 실패했습니다. HTTP 401");
            }
            if (status >= 500) {
                // 서비스 장애: 다른 건도 실패할 것이라 회차를 멈춘다
                throw new DeliveryTrackerServiceDownException("Delivery Tracker가 오류를 돌려줬습니다. HTTP " + status + ", code=" + code);
            }
            if (code != null) {
                // 정상 응답 안의 건별 오류(NOT_FOUND·모르는 코드). 외부 메시지는 담지 않고 코드만 남긴다. 그 건만 실패로 센다
                throw new DeliveryTrackerUnavailableException("Delivery Tracker가 오류를 돌려줬습니다. HTTP " + status + ", code=" + code);
            }
            if (status < 200 || status >= 300 || root == null) {
                throw new DeliveryTrackerServiceDownException("Delivery Tracker 응답이 올바르지 않습니다. HTTP " + status);
            }
        }
    }
}
