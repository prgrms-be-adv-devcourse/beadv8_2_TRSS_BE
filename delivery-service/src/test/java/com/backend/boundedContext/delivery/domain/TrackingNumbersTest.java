package com.backend.boundedContext.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backend.global.exception.DomainException;

class TrackingNumbersTest {

    @Test
    @DisplayName("앞뒤·중간 공백과 하이픈을 지운다")
    void normalize() {
        assertThat(TrackingNumbers.normalize(" 1234-5678 9012 ")).isEqualTo("123456789012");
        assertThat(TrackingNumbers.normalize("AB12cd34")).isEqualTo("AB12cd34");
    }

    @Test
    @DisplayName("정규화 뒤 8자·30자는 허용한다")
    void boundary() {
        assertThat(TrackingNumbers.normalize("12345678")).hasSize(8);
        assertThat(TrackingNumbers.normalize("1".repeat(30))).hasSize(30);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234567", "1234-567", "  ", "123456789012345678901234567890X", "1234567890#", "송장12345678"})
    @DisplayName("정규화 뒤 영문·숫자 8~30자가 아니면 400 DELIVERY_TRACKING_NUMBER_INVALID")
    void invalid(String raw) {
        assertThatThrownBy(() -> TrackingNumbers.normalize(raw))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-DELIVERY_TRACKING_NUMBER_INVALID");
    }
}
