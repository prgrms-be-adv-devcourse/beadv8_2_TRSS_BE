package com.backend.boundedContext.delivery.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * Delivery Tracker 연동 정책 값 (custom.deliveryTracker.*)
 * 키·콜백 주소·토큰은 기본값 없이 환경변수로만 받는다.
 */
@Component
@Getter
public class DeliveryTrackerPolicy {

    // 콜백 토큰 최소 길이
    public static final int MIN_CALLBACK_TOKEN_LENGTH = 32;

    // GraphQL 엔드포인트
    private final String apiUrl;

    // 인증 헤더 Authorization: TRACKQL-API-KEY {clientId}:{clientSecret}
    private final String clientId;
    private final String clientSecret;

    // 콜백 주소 앞부분(토큰 제외). 끝의 /는 뗀다
    private final String callbackBaseUrl;

    // 콜백 URL 마지막 경로. 로그에 남기지 않는다
    private final String callbackToken;

    // 웹훅 등록 유효 시간(시간). 만료 시각 = 등록 시각 + 이 값
    private final int webhookTtlHours;

    // 만료까지 이 시간보다 적게 남으면 다시 등록한다
    private final int webhookRenewBeforeHours;

    // 응답 대기 제한 시간(밀리초).
    private final int readTimeoutMillis;

    // 초당 최대 호출 수 (모든 스케줄러 합계). 프로젝트 기본 할당량(10회/초)의 절반
    private final int maxCallsPerSecond;

    // 동시에 유지할 활성 웹훅 최대 수 (할당량 1,000개보다 여유 있게)
    private final int maxActiveWebhooks;

    // 웹훅 등록·재등록을 켤지. false면 확인 워커·안전망만 동작한다 (HTTPS 전 개발 서버용)
    private final boolean webhookEnabled;

    // 웹훅이 켜져 있을 때 http 콜백 주소를 허용할지. 로컬·테스트 전용 (Delivery Tracker는 https만 받는다)
    private final boolean allowInsecureCallback;

    public DeliveryTrackerPolicy(
            @Value("${custom.deliveryTracker.apiUrl}") String apiUrl,
            @Value("${custom.deliveryTracker.clientId}") String clientId,
            @Value("${custom.deliveryTracker.clientSecret}") String clientSecret,
            @Value("${custom.deliveryTracker.callbackBaseUrl}") String callbackBaseUrl,
            @Value("${custom.deliveryTracker.callbackToken}") String callbackToken,
            @Value("${custom.deliveryTracker.webhookTtlHours}") int webhookTtlHours,
            @Value("${custom.deliveryTracker.webhookRenewBeforeHours}") int webhookRenewBeforeHours,
            @Value("${custom.deliveryTracker.readTimeoutMillis}") int readTimeoutMillis,
            @Value("${custom.deliveryTracker.maxCallsPerSecond}") int maxCallsPerSecond,
            @Value("${custom.deliveryTracker.maxActiveWebhooks}") int maxActiveWebhooks,
            @Value("${custom.deliveryTracker.webhookEnabled:true}") boolean webhookEnabled,
            @Value("${custom.deliveryTracker.allowInsecureCallback:false}") boolean allowInsecureCallback
    ) {
        requireText(apiUrl, "apiUrl");
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
        requireText(callbackBaseUrl, "callbackBaseUrl");
        // 토큰 값은 메시지에 넣지 않는다
        if (callbackToken == null || callbackToken.strip().length() < MIN_CALLBACK_TOKEN_LENGTH) {
            throw new IllegalArgumentException("custom.deliveryTracker.callbackToken은 " + MIN_CALLBACK_TOKEN_LENGTH
                    + "자 이상이어야 합니다. 생성: openssl rand -hex 32");
        }
        if (webhookTtlHours < 1) throw new IllegalArgumentException("custom.deliveryTracker.webhookTtlHours는 1 이상이어야 합니다: " + webhookTtlHours);
        if (webhookRenewBeforeHours < 1 || webhookRenewBeforeHours >= webhookTtlHours) {
            throw new IllegalArgumentException("custom.deliveryTracker.webhookRenewBeforeHours는 1 이상, webhookTtlHours 미만이어야 합니다: " + webhookRenewBeforeHours);
        }
        if (readTimeoutMillis < 1) throw new IllegalArgumentException("custom.deliveryTracker.readTimeoutMillis는 1 이상이어야 합니다: " + readTimeoutMillis);
        if (maxCallsPerSecond < 1) throw new IllegalArgumentException("custom.deliveryTracker.maxCallsPerSecond는 1 이상이어야 합니다: " + maxCallsPerSecond);
        if (maxActiveWebhooks < 0) throw new IllegalArgumentException("custom.deliveryTracker.maxActiveWebhooks는 0 이상이어야 합니다: " + maxActiveWebhooks);
        // Delivery Tracker는 callbackUrl로 https만 받는다(http면 BAD_REQUEST). 등록마다 실패하기 전에 기동에서 막는다.
        // 주소 값은 메시지에 넣지 않는다(토큰은 없지만 서버 주소가 로그에 남지 않게)
        if (webhookEnabled && !allowInsecureCallback && !callbackBaseUrl.strip().toLowerCase().startsWith("https://")) {
            throw new IllegalArgumentException("custom.deliveryTracker.callbackBaseUrl은 https://로 시작해야 합니다(Delivery Tracker는 https 콜백만 받음). "
                    + "HTTPS가 없는 서버면 DELIVERY_TRACKER_WEBHOOK_ENABLED=false로 웹훅을 끄세요");
        }

        this.apiUrl = apiUrl.strip();
        this.clientId = clientId.strip();
        this.clientSecret = clientSecret.strip();
        this.callbackBaseUrl = callbackBaseUrl.strip().replaceAll("/+$", "");
        this.callbackToken = callbackToken.strip();
        this.webhookTtlHours = webhookTtlHours;
        this.webhookRenewBeforeHours = webhookRenewBeforeHours;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxCallsPerSecond = maxCallsPerSecond;
        this.maxActiveWebhooks = maxActiveWebhooks;
        this.webhookEnabled = webhookEnabled;
        this.allowInsecureCallback = allowInsecureCallback;
    }

    /** 콜백 URL의 토큰이 맞는지. 시간 차이로 토큰이 새지 않게 MessageDigest.isEqual로 비교한다 */
    public boolean matchesCallbackToken(String token) {
        if (token == null) return false;
        return MessageDigest.isEqual(
                callbackToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** 웹훅에 등록할 콜백 URL (토큰 포함). 로그에 남기지 않는다 */
    public String callbackUrl() {
        return callbackBaseUrl + "/" + callbackToken;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("custom.deliveryTracker." + name + " 값이 없습니다. 환경변수(.env)를 확인하세요");
        }
    }
}
