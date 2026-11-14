package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementCandidateTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 9, 15, 12, 0);

    @Test
    @DisplayName("후보 생성: 수수료는 품목별 버림, 지급액은 판매가 - 수수료, 정산월은 확정월")
    void create() {
        SettlementCandidate c = SettlementCandidate.create(1L, 11L, 7L, 9_999L, 10, CONFIRMED_AT);

        assertEquals(999L, c.getFee());
        assertEquals(9_000L, c.getPayoutAmount());
        assertEquals(10, c.getFeeRate());
        assertEquals("2026-09", c.getSettlementMonth());
        assertEquals(SettlementCandidateStatus.READY, c.getStatus());
        assertNull(c.getSettlementId());
        assertNull(c.getOutboxEventId());
    }

    @Test
    @DisplayName("READY 후보만 EXCLUDED로 제외할 수 있다")
    void exclude() {
        SettlementCandidate c = SettlementCandidate.create(1L, 11L, 7L, 10_000L, 10, CONFIRMED_AT);
        c.exclude("테스트 주문");

        assertEquals(SettlementCandidateStatus.EXCLUDED, c.getStatus());
        assertEquals("테스트 주문", c.getExcludedReason());
        assertThrows(DomainException.class, () -> c.exclude("두 번째 제외"));
    }

    @Test
    @DisplayName("정산서에 묶인 후보는 제외할 수 없다")
    void exclude_included() {
        SettlementCandidate c = SettlementCandidate.create(1L, 11L, 7L, 10_000L, 10, CONFIRMED_AT);
        Settlement s = Settlement.create(7L, "2026-09", null);
        s.addCandidate(c);

        assertEquals(SettlementCandidateStatus.INCLUDED, c.getStatus());
        DomainException e = assertThrows(DomainException.class, () -> c.exclude("늦은 제외"));
        assertEquals("409-SETTLEMENT_CANDIDATE_STATE_INVALID", e.getResultCode());
    }

    @Test
    @DisplayName("필수 값이 없으면 생성할 수 없다")
    void create_invalid() {
        assertThrows(IllegalArgumentException.class,
                () -> SettlementCandidate.create(null, 11L, 7L, 10_000L, 10, CONFIRMED_AT));
    }
}
