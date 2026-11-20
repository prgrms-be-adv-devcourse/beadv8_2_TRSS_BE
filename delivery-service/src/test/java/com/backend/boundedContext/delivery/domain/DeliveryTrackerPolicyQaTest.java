package com.backend.boundedContext.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * [QA] DeliveryTrackerPolicy: 토큰 비교 경계, https 검사 경계, application.yaml 기본값(env 없음 → 기동 실패)
 */
class DeliveryTrackerPolicyQaTest {

    private static final String TOKEN = "qa-callback-token-abcdefghijklmnopqrstuvwxyz0123";

    private static DeliveryTrackerPolicy policy(String baseUrl, boolean webhookEnabled, boolean allowInsecure) {
        return new DeliveryTrackerPolicy("https://tracker.test/graphql", "id", "secret",
                baseUrl, TOKEN, 48, 24, 15000, 5, 900, webhookEnabled, allowInsecure);
    }

    @Test
    @DisplayName("[QA] 토큰 비교: 정확히 같을 때만 true. 앞부분·뒤에 더 붙음·대소문자·null·빈 값은 false")
    void matchesCallbackToken_boundaries() {
        DeliveryTrackerPolicy p = policy("https://palette.test/webhooks/delivery-tracker", true, false);

        assertThat(p.matchesCallbackToken(TOKEN)).isTrue();
        assertThat(p.matchesCallbackToken(TOKEN.substring(0, TOKEN.length() - 1))).isFalse();
        assertThat(p.matchesCallbackToken(TOKEN + "0")).isFalse();
        assertThat(p.matchesCallbackToken(TOKEN.toUpperCase())).isFalse();
        assertThat(p.matchesCallbackToken(null)).isFalse();
        assertThat(p.matchesCallbackToken("")).isFalse();
    }

    @Test
    @DisplayName("[QA] https 검사: 대문자 HTTPS·앞뒤 공백·끝 슬래시는 허용, callbackUrl은 슬래시 하나로 붙는다")
    void https_variants() {
        assertThatCode(() -> policy("HTTPS://palette.test/webhooks/delivery-tracker", true, false)).doesNotThrowAnyException();
        DeliveryTrackerPolicy p = policy("  https://palette.test/webhooks/delivery-tracker//  ", true, false);

        assertThat(p.callbackUrl()).isEqualTo("https://palette.test/webhooks/delivery-tracker/" + TOKEN);
    }

    @Test
    @DisplayName("[QA] 운영 조합(웹훅 켬, http 불허)에서 http·ftp·스킴 없는 주소 → 기동 실패, 메시지에 주소·토큰 없음")
    void insecure_failsWithoutLeaking() {
        for (String url : new String[]{"http://10.0.0.1/webhooks/delivery-tracker", "ftp://palette.test/x", "palette.test/webhooks"}) {
            assertThatThrownBy(() -> policy(url, true, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DELIVERY_TRACKER_WEBHOOK_ENABLED=false")
                    .hasMessageNotContaining(url)
                    .hasMessageNotContaining(TOKEN);
        }
    }

    @Test
    @DisplayName("[QA] 웹훅 끔이면 http 주소로도 뜨지만, 콜백 토큰·주소는 여전히 필수(D4)")
    void webhookDisabled_stillRequiresTokenAndUrl() {
        assertThatCode(() -> policy("http://10.0.0.1/webhooks/delivery-tracker", false, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy("  ", false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryTrackerPolicy("https://tracker.test/graphql", "id", "secret",
                "http://10.0.0.1/x", "short", 48, 24, 15000, 5, 900, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("short");
    }

    @Test
    @DisplayName("[QA] main application.yaml만으로(DELIVERY_TRACKER_* env 없음) 정책 빈을 만들면 기동 실패 — 테스트 properties가 없으면 깨진다(D4)")
    void applicationYaml_withoutEnv_failsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeliveryTrackerPolicy.class)
                .withPropertyValues(
                        // application.yaml의 기본값 없는 자리표시자를 그대로 흉내 낸다
                        "custom.deliveryTracker.apiUrl=https://apis.tracker.delivery/graphql",
                        "custom.deliveryTracker.clientId=${DELIVERY_TRACKER_CLIENT_ID_QA_UNSET}",
                        "custom.deliveryTracker.clientSecret=${DELIVERY_TRACKER_CLIENT_SECRET_QA_UNSET}",
                        "custom.deliveryTracker.callbackBaseUrl=${DELIVERY_TRACKER_CALLBACK_BASE_URL_QA_UNSET}",
                        "custom.deliveryTracker.callbackToken=${DELIVERY_TRACKER_CALLBACK_TOKEN_QA_UNSET}",
                        "custom.deliveryTracker.webhookTtlHours=48",
                        "custom.deliveryTracker.webhookRenewBeforeHours=24",
                        "custom.deliveryTracker.readTimeoutMillis=15000",
                        "custom.deliveryTracker.maxCallsPerSecond=5",
                        "custom.deliveryTracker.maxActiveWebhooks=900")
                .run(context -> assertThat(context).hasFailed());
    }
}
