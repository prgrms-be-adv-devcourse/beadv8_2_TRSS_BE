package com.backend.boundedContext.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeliveryTrackerPolicyTest {

    private static final String TOKEN = "test-callback-token-0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DeliveryTrackerPolicy.class)
            .withPropertyValues(
                    "custom.deliveryTracker.apiUrl=https://tracker.test/graphql",
                    "custom.deliveryTracker.clientId=id",
                    "custom.deliveryTracker.clientSecret=secret",
                    "custom.deliveryTracker.callbackBaseUrl=https://palette.test/webhooks/delivery-tracker/",
                    "custom.deliveryTracker.callbackToken=" + TOKEN,
                    "custom.deliveryTracker.webhookTtlHours=48",
                    "custom.deliveryTracker.webhookRenewBeforeHours=24",
                    "custom.deliveryTracker.readTimeoutMillis=15000",
                    "custom.deliveryTracker.maxCallsPerSecond=5",
                    "custom.deliveryTracker.maxActiveWebhooks=900");

    @Test
    @DisplayName("콜백 토큰이 32자 미만이면 기동에 실패하고, 메시지에 토큰 값이 없다")
    void shortToken_failsStartup() {
        runner.withPropertyValues("custom.deliveryTracker.callbackToken=short-token-1234")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("32자 이상")
                            .hasMessageNotContaining("short-token-1234");
                });
    }

    @Test
    @DisplayName("키가 비어 있으면 기동에 실패한다 (결정 D4: 기본값 없음)")
    void blankClientId_failsStartup() {
        runner.withPropertyValues("custom.deliveryTracker.clientId=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("토큰 비교: 같으면 true, 다르거나 null이면 false. 콜백 URL은 끝의 /를 떼고 토큰을 붙인다")
    void matchesAndCallbackUrl() {
        runner.run(context -> {
            DeliveryTrackerPolicy policy = context.getBean(DeliveryTrackerPolicy.class);
            assertThat(policy.matchesCallbackToken(TOKEN)).isTrue();
            assertThat(policy.matchesCallbackToken(TOKEN + "x")).isFalse();
            assertThat(policy.matchesCallbackToken("wrong")).isFalse();
            assertThat(policy.matchesCallbackToken(null)).isFalse();
            assertThat(policy.callbackUrl()).isEqualTo("https://palette.test/webhooks/delivery-tracker/" + TOKEN);
        });
    }

    @Test
    @DisplayName("재등록 기준 시간이 유효 시간 이상이면 생성 실패")
    void renewBeforeMustBeLessThanTtl() {
        assertThatThrownBy(() -> new DeliveryTrackerPolicy("u", "id", "s", "https://x", TOKEN, 24, 24, 15000, 5, 900, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("웹훅이 켜져 있고 콜백 주소가 http면 기동에 실패하고, 메시지에 주소·토큰이 없다")
    void httpCallback_failsStartup() {
        runner.withPropertyValues("custom.deliveryTracker.callbackBaseUrl=http://10.0.0.1/webhooks/delivery-tracker")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("https://")
                            .hasMessageContaining("DELIVERY_TRACKER_WEBHOOK_ENABLED=false")
                            .hasMessageNotContaining("10.0.0.1")
                            .hasMessageNotContaining(TOKEN);
                });
    }

    @Test
    @DisplayName("http 콜백 주소: allowInsecureCallback=true(로컬·테스트)거나 웹훅이 꺼져 있으면 뜬다")
    void httpCallback_allowedWhenInsecureFlagOrWebhookDisabled() {
        String http = "custom.deliveryTracker.callbackBaseUrl=http://localhost:8085/webhooks/delivery-tracker";
        runner.withPropertyValues(http, "custom.deliveryTracker.allowInsecureCallback=true")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(http, "custom.deliveryTracker.webhookEnabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DeliveryTrackerPolicy.class).isWebhookEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("기본값: 웹훅 켜짐, http 허용 안 함")
    void defaults() {
        runner.run(context -> {
            DeliveryTrackerPolicy policy = context.getBean(DeliveryTrackerPolicy.class);
            assertThat(policy.isWebhookEnabled()).isTrue();
            assertThat(policy.isAllowInsecureCallback()).isFalse();
        });
    }
}
