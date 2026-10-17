package com.backend.boundedContext.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("주문 항목 금액은 0원보다 크고 단가 × 수량과 같아야 한다")
    void isValidLineAmount() {
        assertTrue(SettlementPolicy.isValidLineAmount(5_000L, 3, 15_000L));
        assertFalse(SettlementPolicy.isValidLineAmount(5_000L, 3, 14_000L));   // 단가 × 수량 불일치
        assertFalse(SettlementPolicy.isValidLineAmount(0L, 1, 0L));           // 0원
        assertFalse(SettlementPolicy.isValidLineAmount(-1L, 1, -1L));         // 음수
        assertFalse(SettlementPolicy.isValidLineAmount(Long.MAX_VALUE, 2, 1L)); // 곱셈 오버플로
    }

    @Test
    @DisplayName("정산월은 구매 확정 시각(KST)이 속한 달")
    void toSettlementMonth() {
        assertEquals("2026-09", SettlementPolicy.toSettlementMonth(LocalDateTime.of(2026, 9, 30, 23, 59, 59)));
        assertEquals("2026-10", SettlementPolicy.toSettlementMonth(LocalDateTime.of(2026, 10, 1, 0, 0)));
    }

    @Test
    @DisplayName("끝난 달만 정산할 수 있다")
    void isClosedMonth() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 0, 0);
        assertTrue(SettlementPolicy.isClosedMonth(YearMonth.of(2026, 9), now));
        assertFalse(SettlementPolicy.isClosedMonth(YearMonth.of(2026, 10), now));
    }

    @Test
    @DisplayName("멱등 키는 SETTLEMENT-{id}")
    void idempotencyKeyOf() {
        assertEquals("SETTLEMENT-31", SettlementPolicy.idempotencyKeyOf(31L));
    }

    @Test
    @DisplayName("금액 불일치·판매자/지갑 없음은 재시도 불가, 그 밖은 재시도 대상")
    void isNonRetryable() {
        assertTrue(SettlementPolicy.isNonRetryable("409-SETTLEMENT_AMOUNT_MISMATCH"));
        assertTrue(SettlementPolicy.isNonRetryable("404-SELLER_NOT_FOUND"));
        assertTrue(SettlementPolicy.isNonRetryable("404-WALLET_NOT_FOUND"));
        assertTrue(SettlementPolicy.isNonRetryable("404-HOLDING_NOT_FOUND"));
        assertTrue(SettlementPolicy.isNonRetryable("409-HOLDING_INSUFFICIENT"));
        assertTrue(SettlementPolicy.isNonRetryable("409-TRANSFER_AMOUNT_MISMATCH"));
        assertFalse(SettlementPolicy.isNonRetryable("IllegalStateException"));
        assertFalse(SettlementPolicy.isNonRetryable(null));
    }

    // 10/14 리뷰 반영: 값을 생성자 @Value로 받게 바꾸면서 @Validated(@Min·@Max)가 하던 범위 검사를 생성자가 맡았다.
    // 손으로 짠 검사라 아래 두 테스트로 동작을 고정한다.
    @Test
    @DisplayName("정책 값이 범위를 벗어나면 만들 때 예외(스프링에서는 앱이 뜨지 않음)")
    void constructor_invalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new SettlementPolicy(101, 10, 3, 10, 100, 50)); // 수수료율 > 100
        assertThrows(IllegalArgumentException.class, () -> new SettlementPolicy(-1, 10, 3, 10, 100, 50));  // 수수료율 < 0
        assertThrows(IllegalArgumentException.class, () -> new SettlementPolicy(10, 29, 3, 10, 100, 50));  // 정산일 > 28
        assertThrows(IllegalArgumentException.class, () -> new SettlementPolicy(10, 10, 0, 10, 100, 50));  // 시도 횟수 0
        assertThrows(IllegalArgumentException.class, () -> new SettlementPolicy(10, 10, 3, 10, 0, 50));   // 처리 건수 0
    }

    @Test
    @DisplayName("정상 값이면 스프링 없이 만들어 그대로 꺼내 쓸 수 있다(0으로 남지 않음)")
    void constructor_validValues() {
        SettlementPolicy policy = new SettlementPolicy(10, 10, 3, 10, 100, 50);
        assertEquals(10, policy.getFeeRate());
        assertEquals(3, policy.getMaxAttempts());
        assertEquals(1_000L, SettlementPolicy.calculateFee(10_000L, policy.getFeeRate()));
    }
}