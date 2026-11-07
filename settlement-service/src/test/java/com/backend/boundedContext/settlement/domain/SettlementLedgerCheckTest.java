package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.TransferResultDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원장 무결성: 후보 금액 스냅샷 확인(1단계)과 지급 영수증 재대조(2단계 ②)
 */
class SettlementLedgerCheckTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 0, 0);
    private Settlement paid;

    @BeforeEach
    void setUp() {
        paid = Settlement.create(7L, "2026-09", null);
        paid.addCandidate(SettlementCandidate.create(100L, 1001L, 7L, 20_000L, 10, NOW.minusDays(20)));
        paid.markPaid(501L, NOW);   // 지급액 18,000, 수수료 2,000, 원장 행 501
    }

    @Test
    @DisplayName("후보 금액 스냅샷: 수수료 = 판매가 × 요율 ÷ 100(버림), 지급액 = 판매가 − 수수료일 때만 맞다")
    void candidateAmounts() throws Exception {
        SettlementCandidate candidate = SettlementCandidate.create(100L, 1002L, 7L, 9_999L, 10, NOW);
        assertTrue(candidate.hasConsistentAmounts());

        SettlementJudgementTest.setField(candidate, "fee", 1_000L);
        assertFalse(candidate.hasConsistentAmounts());
    }

    @Test
    @DisplayName("원장을 다시 읽은 값이 영수증과 같으면 OK")
    void ok() {
        assertEquals(SettlementLedgerCheckResult.OK, paid.checkLedger(ledger(501L, 18_000L, 2_000L)));
    }

    @Test
    @DisplayName("원장 기록이 없으면 MISSING, 행 ID가 다르면 REPLACED, 금액이 다르면 AMOUNT_CHANGED")
    void mismatches() {
        assertEquals(SettlementLedgerCheckResult.MISSING, paid.checkLedger(null));
        assertEquals(SettlementLedgerCheckResult.REPLACED, paid.checkLedger(ledger(999L, 18_000L, 2_000L)));
        assertEquals(SettlementLedgerCheckResult.AMOUNT_CHANGED, paid.checkLedger(ledger(501L, 17_000L, 2_000L)));
        assertEquals(SettlementLedgerCheckResult.AMOUNT_CHANGED, paid.checkLedger(ledger(501L, 18_000L, 2_500L)));
        assertEquals("409-SETTLEMENT_LEDGER_MISSING", SettlementLedgerCheckResult.MISSING.resultCode());
    }

    @Test
    @DisplayName("지급되지 않은 정산서는 재대조할 수 없다")
    void onlyPaid() {
        Settlement ready = Settlement.create(7L, "2026-09", null);
        assertThrows(DomainException.class, () -> ready.checkLedger(ledger(1L, 0L, 0L)));
    }

    private static TransferResultDto ledger(Long walletTxId, long payout, long fee) {
        return new TransferResultDto("SETTLEMENT-1", walletTxId, payout, fee, NOW);
    }
}
