package com.backend.shared.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.payment.dto.PayCommand;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결제 요청 계약: market → payment HTTP 본문(PayCommand)의 JSON 모양.
 * 배송비·장바구니 쿠폰 지원금은 필수 필드다. 보내는 쪽은 값이 없으면 0을 보내야 하고, 빠지면 거절된다.
 */
class PayCommandJsonTest {

    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 11, 11, 10, 15);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("배송비·지원금이 있는 본문은 그 값으로 읽는다")
    void readsShippingFeeAndCouponSubsidy() {
        PayCommand command = jsonMapper.readValue("""
                {"orderId": 1, "orderNumber": "ORD-1", "buyerId": 3, "amount": 50000,
                 "shippingFee": 3000, "couponSubsidyAmount": 5000,
                 "orderExpiresAt": "2026-11-11T10:15:00", "idempotencyKey": "ORDER-1-PAY-a"}
                """, PayCommand.class);

        assertThat(command).isEqualTo(new PayCommand(1L, "ORD-1", 3L, 50_000, 3_000, 5_000,
                EXPIRES_AT, "ORDER-1-PAY-a"));
    }

    @Test
    @DisplayName("배송비·지원금이 0이면 6인자 생성자로 만든 값과 같다")
    void zeroFields_equalLegacyConstructor() {
        PayCommand command = jsonMapper.readValue("""
                {"orderId": 1, "orderNumber": "ORD-1", "buyerId": 3, "amount": 50000,
                 "shippingFee": 0, "couponSubsidyAmount": 0,
                 "orderExpiresAt": "2026-11-11T10:15:00", "idempotencyKey": "ORDER-1-PAY-a"}
                """, PayCommand.class);

        assertThat(command).isEqualTo(new PayCommand(1L, "ORD-1", 3L, 50_000, EXPIRES_AT, "ORDER-1-PAY-a"));
    }

    @Test
    @DisplayName("배송비·지원금이 빠진 본문은 거절한다 (필수 필드)")
    void missingFields_areRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("""
                {"orderId": 1, "orderNumber": "ORD-1", "buyerId": 3, "amount": 50000,
                 "orderExpiresAt": "2026-11-11T10:15:00", "idempotencyKey": "ORDER-1-PAY-a"}
                """, PayCommand.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("배송비·지원금이 null인 본문은 거절한다")
    void nullFields_areRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("""
                {"orderId": 1, "orderNumber": "ORD-1", "buyerId": 3, "amount": 50000,
                 "shippingFee": null, "couponSubsidyAmount": null,
                 "orderExpiresAt": "2026-11-11T10:15:00", "idempotencyKey": "ORDER-1-PAY-a"}
                """, PayCommand.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("직렬화하면 배송비·지원금이 필드 이름 그대로 실린다 (6인자 생성자로 만들어도 0으로 실린다)")
    void writesShippingFeeAndCouponSubsidy() {
        PayCommand command = new PayCommand(1L, "ORD-1", 3L, 50_000, 3_000, 5_000, EXPIRES_AT, "ORDER-1-PAY-a");
        PayCommand legacy = new PayCommand(1L, "ORD-1", 3L, 50_000, EXPIRES_AT, "ORDER-1-PAY-a");

        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(command));
        JsonNode legacyJson = jsonMapper.readTree(jsonMapper.writeValueAsString(legacy));

        assertThat(json.get("shippingFee").asLong()).isEqualTo(3_000);
        assertThat(json.get("couponSubsidyAmount").asLong()).isEqualTo(5_000);
        assertThat(legacyJson.get("shippingFee").asLong()).isZero();
        assertThat(legacyJson.get("couponSubsidyAmount").asLong()).isZero();
    }
}
