package com.backend.shared.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.payment.dto.PayCommand;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * QA 보강: PayCommand의 배송비·지원금 필드가 Jackson 3 기본값(FAIL_ON_NULL_FOR_PRIMITIVES=true) 위에서
 * 필수 필드로 동작하는지(빠지거나 null이면 거절) 확인한다.
 */
class PayCommandJsonQaTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("전제: 이 프로젝트 Jackson 3 기본값은 FAIL_ON_NULL_FOR_PRIMITIVES = true")
    void jackson3Default_failOnNullForPrimitives() {
        assertThat(jsonMapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)).isTrue();
    }

    @Test
    @DisplayName("shippingFee만 있고 couponSubsidyAmount가 빠진 본문은 거절된다")
    void missingCouponSubsidy_isRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("""
                {"orderId": 1, "orderNumber": "ORD-1", "buyerId": 3, "amount": 50000, "shippingFee": 2500,
                 "orderExpiresAt": "2026-11-11T10:15:00", "idempotencyKey": "ORDER-1-PAY-a"}
                """, PayCommand.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("couponSubsidyAmount");
    }

    @Test
    @DisplayName("shippingFee가 명시적 null이면 거절된다")
    void explicitNullShippingFee_isRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("""
                {"orderId": 1, "orderNumber": "ORD-1", "buyerId": 3, "amount": 50000,
                 "shippingFee": null, "couponSubsidyAmount": 0,
                 "orderExpiresAt": "2026-11-11T10:15:00", "idempotencyKey": "ORDER-1-PAY-a"}
                """, PayCommand.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("shippingFee");
    }

    @Test
    @DisplayName("필드 순서가 달라도(배송비·지원금이 맨 뒤) 이름으로 읽는다")
    void newFieldsLast_readsByName() {
        PayCommand command = jsonMapper.readValue("""
                {"idempotencyKey": "ORDER-1-PAY-a", "orderExpiresAt": "2026-11-11T10:15:00",
                 "amount": 52500, "buyerId": 3, "orderNumber": "ORD-1", "orderId": 1,
                 "couponSubsidyAmount": 3000, "shippingFee": 2500}
                """, PayCommand.class);

        assertThat(command.amount()).isEqualTo(52_500);
        assertThat(command.shippingFee()).isEqualTo(2_500);
        assertThat(command.couponSubsidyAmount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("amount가 빠진 본문도 거절된다")
    void missingAmount_isRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("""
                {"orderId": 1, "orderNumber": "ORD-1", "buyerId": 3, "shippingFee": 2500, "couponSubsidyAmount": 0,
                 "orderExpiresAt": "2026-11-11T10:15:00", "idempotencyKey": "ORDER-1-PAY-a"}
                """, PayCommand.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("직렬화 → 역직렬화 왕복이 같은 값을 만든다 (6인자 생성자 = 배송비·지원금 0)")
    void roundTrip() {
        PayCommand legacy = new PayCommand(1L, "ORD-1", 3L, 50_000,
                LocalDateTime.of(2026, 11, 11, 10, 15), "ORDER-1-PAY-a");

        String json = jsonMapper.writeValueAsString(legacy);

        assertThat(jsonMapper.readTree(json).get("shippingFee").asLong()).isZero();
        assertThat(jsonMapper.readTree(json).get("couponSubsidyAmount").asLong()).isZero();
        assertThat(jsonMapper.readValue(json, PayCommand.class)).isEqualTo(legacy);
    }
}
