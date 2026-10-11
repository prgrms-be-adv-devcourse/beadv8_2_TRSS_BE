package com.backend.boundedContext.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementPolicyTest {

    @Test
    @DisplayName("수수료는 판매가 × 수수료율 ÷ 100, 곱셈 먼저 하고 버림")
    void calculateFee_floor() {
        assertEquals(1_000L, SettlementPolicy.calculateFee(10_000L, 10));
        assertEquals(999L, SettlementPolicy.calculateFee(9_999L, 10));   // 999.9 → 999
        assertEquals(0L, SettlementPolicy.calculateFee(9L, 10));         // 0.9 → 0
        assertEquals(0L, SettlementPolicy.calculateFee(0L, 10));
    }

    @Test
    @DisplayName("수수료율·판매가 범위를 벗어나면 예외")
    void calculateFee_invalid() {
        assertThrows(IllegalArgumentException.class, () -> SettlementPolicy.calculateFee(-1L, 10));
        assertThrows(IllegalArgumentException.class, () -> SettlementPolicy.calculateFee(100L, 101));
    }

    @Test
    @DisplayName("정산월은 구매 확정 시각(KST)이 속한 달")
    void toSettlementMonth() {
        assertEquals("2026-09", SettlementPolicy.toSettlementMonth(LocalDateTime.of(2026, 9, 30, 23, 59, 59)));
        assertEquals("2026-10", SettlementPolicy.toSettlementMonth(LocalDateTime.of(2026, 10, 1, 0, 0)));
    }

    @Test
    @DisplayName("멱등 키는 SETTLEMENT-{id}")
    void idempotencyKeyOf() {
        assertEquals("SETTLEMENT-31", SettlementPolicy.idempotencyKeyOf(31L));
    }
}
