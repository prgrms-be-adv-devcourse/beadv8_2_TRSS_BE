package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backend.global.exception.DomainException;

class PaymentPolicyTest {

    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 1L);

    @ParameterizedTest
    @ValueSource(longs = {10_000, 20_000, 990_000, 1_000_000})
    @DisplayName("1만 원 단위, 1만~100만 원이면 통과한다")
    void validateTopupAmount_valid(long amount) {
        assertThatCode(() -> paymentPolicy.validateTopupAmount(amount)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {-10_000, 0, 5_000, 15_000, 1_010_000})
    @DisplayName("단위가 맞지 않거나 범위를 벗어나면 TOPUP_AMOUNT_INVALID")
    void validateTopupAmount_invalid(long amount) {
        assertThatThrownBy(() -> paymentPolicy.validateTopupAmount(amount))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-TOPUP_AMOUNT_INVALID");
    }

    @ParameterizedTest(name = "부족액 {0} → 충전 권장액 {1}")
    @CsvSource({"1, 10000", "9999, 10000", "10000, 10000", "18000, 20000", "20000, 20000", "20001, 30000"})
    @DisplayName("충전 권장액은 부족액을 충전 단위(1만 원)로 올린 금액이다")
    void recommendedChargeAmount(long shortage, long expected) {
        assertThat(paymentPolicy.recommendedChargeAmount(shortage)).isEqualTo(expected);
    }

    @Test
    @DisplayName("부족액이 없으면 충전 권장액도 0이다")
    void recommendedChargeAmount_noShortage() {
        assertThat(paymentPolicy.recommendedChargeAmount(0)).isZero();
    }

    @Test
    @DisplayName("READY 만료 시간은 설정한 분 단위 값이다")
    void topupReadyTtl() {
        assertThat(paymentPolicy.topupReadyTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("결과 확인 대기 시간과 1회 처리 건수는 설정한 값이다")
    void topupReconcile() {
        assertThat(paymentPolicy.topupReconcileDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(paymentPolicy.topupReconcileBatchSize()).isEqualTo(100);
    }
}
