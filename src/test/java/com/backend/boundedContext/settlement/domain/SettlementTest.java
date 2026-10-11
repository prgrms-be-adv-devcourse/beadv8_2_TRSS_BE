package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 0, 0);
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_MINUTES = 10;

    @Test
    @DisplayName("후보를 담으면 합계와 항목 수가 함께 오른다")
    void addCandidate() {
        Settlement s = Settlement.create(7L, "2026-09", null);
        s.addCandidate(candidate(11L, 10_000L));
        s.addCandidate(candidate(12L, 9_999L));

        assertEquals(SettlementStatus.READY, s.getStatus());
        assertEquals(19_999L, s.getTotalSalePrice());
        assertEquals(1_999L, s.getTotalFee());           // 1,000 + 999 (품목별 버림 후 합산)
        assertEquals(18_000L, s.getPayoutAmount());
        assertEquals(2, s.getItemCount());
        assertEquals(s.getTotalSalePrice(), s.getTotalFee() + s.getPayoutAmount());
    }

    @Test
    @DisplayName("holdReason이 있으면 처음부터 HOLD로 만들고, HOLD에도 후보를 담을 수 있다")
    void createHold() {
        Settlement s = Settlement.create(7L, "2026-09", "제재");
        assertEquals(SettlementStatus.HOLD, s.getStatus());
        assertEquals("제재", s.getHoldReason());
        s.addCandidate(candidate(11L, 10_000L));
        assertEquals(1, s.getItemCount());
    }

    @Test
    @DisplayName("멱등 키는 저장 후에만 정할 수 있고 한 번 정하면 바뀌지 않는다")
    void assignIdempotencyKey() {
        Settlement s = Settlement.create(7L, "2026-09", null);
        assertThrows(IllegalStateException.class, s::assignIdempotencyKey);

        ReflectionTestUtils.setField(s, "id", 31L);
        s.assignIdempotencyKey();
        assertEquals("SETTLEMENT-31", s.getIdempotencyKey());

        ReflectionTestUtils.setField(s, "id", 99L);
        s.assignIdempotencyKey();
        assertEquals("SETTLEMENT-31", s.getIdempotencyKey());
    }

    @Test
    @DisplayName("자동 지급: 실패 1·2회째는 FAILED + 10분 뒤 재시도, 3회째는 MANUAL_REQUIRED")
    void markFailed_untilManual() {
        Settlement s = processing();

        s.markFailed("1차", NOW, MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertEquals(NOW.plusMinutes(10), s.getNextRetryAt());

        ReflectionTestUtils.setField(s, "status", SettlementStatus.PROCESSING);   // 10분 뒤 재선점
        s.markFailed("2차", NOW.plusMinutes(10), MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(2, s.getRetryCount());

        ReflectionTestUtils.setField(s, "status", SettlementStatus.PROCESSING);
        s.markFailed("3차", NOW.plusMinutes(20), MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(3, s.getRetryCount());
        assertNull(s.getNextRetryAt());
        assertEquals("3차", s.getLastFailReason());
    }

    @Test
    @DisplayName("실패 사유는 300자로 자른다")
    void markFailed_truncate() {
        Settlement s = processing();
        s.markFailed("x".repeat(500), NOW, MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(300, s.getLastFailReason().length());
    }

    @Test
    @DisplayName("지급 성공은 PROCESSING에서만, 재시도 시각을 지운다")
    void markPaid() {
        Settlement s = processing();
        s.markPaid(501L, NOW);

        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(501L, s.getWalletTxId());
        assertEquals(NOW, s.getPaidAt());
        assertThrows(DomainException.class, () -> s.markPaid(502L, NOW));
    }

    @Test
    @DisplayName("금액 불일치·수동 재처리 실패는 바로 MANUAL_REQUIRED")
    void markManualRequired() {
        Settlement s = processing();
        s.markManualRequired("금액 불일치");
        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(1, s.getRetryCount());
    }

    @Test
    @DisplayName("송금 기록 금액 비교")
    void matchesTransfer() {
        Settlement s = Settlement.create(7L, "2026-09", null);
        s.addCandidate(candidate(11L, 10_000L));

        assertTrue(s.matchesTransfer(9_000L, 1_000L));
        assertFalse(s.matchesTransfer(9_000L, 999L));
    }

    @Test
    @DisplayName("보류는 READY·FAILED·MANUAL_REQUIRED만, PROCESSING·PAID·HOLD는 409")
    void hold() {
        Settlement ready = Settlement.create(7L, "2026-09", null);
        ready.hold("관리자 확인");
        assertEquals(SettlementStatus.HOLD, ready.getStatus());
        assertThrows(DomainException.class, () -> ready.hold("두 번"));

        Settlement failed = processing();
        failed.markFailed("실패", NOW, MAX_ATTEMPTS, RETRY_MINUTES);
        failed.hold("보류");
        assertEquals(SettlementStatus.HOLD, failed.getStatus());
        assertNull(failed.getNextRetryAt());

        Settlement processing = processing();
        DomainException e = assertThrows(DomainException.class, () -> processing.hold("송금 중"));
        assertEquals("409-SETTLEMENT_STATE_INVALID", e.getResultCode());
    }

    @Test
    @DisplayName("보류 해제는 HOLD → READY, 사유를 지운다")
    void release() {
        Settlement s = Settlement.create(7L, "2026-09", "제재");
        s.release();
        assertEquals(SettlementStatus.READY, s.getStatus());
        assertNull(s.getHoldReason());
        assertThrows(DomainException.class, s::release);
    }

    @Test
    @DisplayName("지급이 시작·완료된 정산서에는 후보를 더 담을 수 없다(이월)")
    void canAcceptCandidates() {
        Settlement s = processing();
        assertFalse(s.canAcceptCandidates());
        assertThrows(DomainException.class, () -> s.addCandidate(candidate(13L, 1_000L)));
    }

    private static Settlement processing() {
        Settlement s = Settlement.create(7L, "2026-09", null);
        s.addCandidate(candidate(11L, 10_000L));
        ReflectionTestUtils.setField(s, "status", SettlementStatus.PROCESSING);   // 선점은 조건부 UPDATE라 테스트에서는 직접 넣는다
        return s;
    }

    private static SettlementCandidate candidate(Long orderItemId, long salePrice) {
        return SettlementCandidate.create(1L, orderItemId, 7L, salePrice, 10, LocalDateTime.of(2026, 9, 15, 12, 0));
    }
}
