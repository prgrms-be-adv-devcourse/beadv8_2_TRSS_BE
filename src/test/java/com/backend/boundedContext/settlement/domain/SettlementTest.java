package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.stream.Stream;

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
    @DisplayName("후보를 담으면 합계와 항목 수가 함께 오르고, 후보 합계와 일치한다")
    void addCandidate() {
        Settlement s = ready();
        s.addCandidate(candidate(12L, 9_999L));

        assertEquals(SettlementStatus.READY, s.getStatus());
        assertEquals(19_999L, s.getTotalSalePrice());
        assertEquals(1_999L, s.getTotalFee());           // 1,000 + 999 (품목별 버림 후 합산)
        assertEquals(18_000L, s.getPayoutAmount());
        assertEquals(2, s.getItemCount());
        assertTrue(s.matchesCandidateTotals(19_999L, 1_999L, 18_000L, 2));
        assertFalse(s.matchesCandidateTotals(19_999L, 1_999L, 18_000L, 3));
    }

    @Test
    @DisplayName("holdReason이 있으면 처음부터 HOLD로 만들고, HOLD에도 후보를 담을 수 있다")
    void createHold() {
        Settlement s = Settlement.create(7L, "2026-09", "제재");
        assertEquals(SettlementStatus.HOLD, s.getStatus());
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
    @DisplayName("자동 경로는 READY와 재시도 일시가 지난 FAILED만, 관리자는 MANUAL_REQUIRED만 지급할 수 있다")
    void isPayable() {
        Settlement s = ready();
        assertTrue(s.isPayable(false, NOW));
        assertTrue(s.isPayable(false, NOW));
        assertFalse(s.isPayable(true, NOW));

        s.markFailed("1차", NOW, MAX_ATTEMPTS, RETRY_MINUTES);   // 다음 재시도 NOW + 10분
        assertFalse(s.isPayable(false, NOW.plusMinutes(9)));
        assertTrue(s.isPayable(false, NOW.plusMinutes(10)));

        s.markManualRequired("금액 불일치");
        assertFalse(s.isPayable(false, NOW.plusDays(1)));   // 자동 재시도 멈춤
        assertTrue(s.isPayable(true, NOW));
    }

    @Test
    @DisplayName("실패 1·2회째는 FAILED + 재시도 간격 뒤 재시도, 3회째는 MANUAL_REQUIRED")
    void markFailed_untilManual() {
        Settlement s = ready();

        s.markFailed("1차", NOW, MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertEquals(NOW.plusMinutes(10), s.getNextRetryAt());
        assertEquals(2, s.nextAttemptNo());

        s.markFailed("2차", NOW.plusMinutes(10), MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(2, s.getRetryCount());

        s.markFailed("3차", NOW.plusMinutes(20), MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(3, s.getRetryCount());
        assertNull(s.getNextRetryAt());
        assertEquals("3차", s.getLastFailReason());
    }

    @Test
    @DisplayName("운영 재시도 간격 2시간이면 다음 재시도 일시도 2시간 뒤")
    void markFailed_productionInterval() {
        Settlement s = ready();
        s.markFailed("1차", NOW, MAX_ATTEMPTS, 120);
        assertEquals(NOW.plusHours(2), s.getNextRetryAt());
    }

    @Test
    @DisplayName("실패 사유는 300자로 자른다")
    void markFailed_truncate() {
        Settlement s = ready();
        s.markFailed("x".repeat(500), NOW, MAX_ATTEMPTS, RETRY_MINUTES);
        assertEquals(300, s.getLastFailReason().length());
    }

    @Test
    @DisplayName("지급 성공은 지급 가능한 상태에서만, 이미 PAID면 다시 지급할 수 없다")
    void markPaid() {
        Settlement s = ready();
        s.markPaid(501L, NOW);

        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(501L, s.getWalletTxId());
        assertEquals(NOW, s.getPaidAt());
        assertFalse(s.isPayableStatus());
        assertFalse(s.isPayable(false, NOW));
        DomainException e = assertThrows(DomainException.class, () -> s.markPaid(502L, NOW));
        assertEquals("409-SETTLEMENT_STATE_INVALID", e.getResultCode());
    }

    @Test
    @DisplayName("재시도 불가 사유·관리자 재처리 실패는 바로 MANUAL_REQUIRED")
    void markManualRequired() {
        Settlement s = ready();
        s.markManualRequired("금액 불일치");
        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertNull(s.getNextRetryAt());
    }

    @Test
    @DisplayName("지급 내역 금액 비교")
    void matchesTransfer() {
        Settlement s = ready();
        assertTrue(s.matchesTransfer(9_000L, 1_000L));
        assertFalse(s.matchesTransfer(9_000L, 999L));
    }

    @Test
    @DisplayName("보류는 READY·FAILED·MANUAL_REQUIRED만, PAID·HOLD는 409")
    void hold() {
        Settlement r = ready();
        r.hold("관리자 확인");
        assertEquals(SettlementStatus.HOLD, r.getStatus());
        assertFalse(r.isPayable(false, NOW));
        assertThrows(DomainException.class, () -> r.hold("두 번"));

        Settlement failed = ready();
        failed.markFailed("실패", NOW, MAX_ATTEMPTS, RETRY_MINUTES);
        failed.hold("보류");
        assertNull(failed.getNextRetryAt());

        Settlement paid = ready();
        paid.markPaid(1L, NOW);
        assertThrows(DomainException.class, () -> paid.hold("지급 후"));
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
    @DisplayName("지급된 정산서에는 후보를 더 담을 수 없다(다음 달로 이월)")
    void canAcceptCandidates() {
        Settlement s = ready();
        s.markPaid(1L, NOW);
        assertFalse(s.canAcceptCandidates());
        assertThrows(DomainException.class, () -> s.addCandidate(candidate(13L, 1_000L)));
    }

    /**
     * 상태 전이표 : 현재 상태 × 메서드 → 기대 상태. 기대 상태가 null이면 409 SETTLEMENT_STATE_INVALID.
     * 규칙을 바꾸면 이 표부터 고친다.
     */
    static Stream<Arguments> transitions() {
        return Stream.of(
                Arguments.of(SettlementStatus.READY, "markPaid", SettlementStatus.PAID),
                Arguments.of(SettlementStatus.READY, "markFailed", SettlementStatus.FAILED),
                Arguments.of(SettlementStatus.READY, "markManualRequired", SettlementStatus.MANUAL_REQUIRED),
                Arguments.of(SettlementStatus.READY, "hold", SettlementStatus.HOLD),
                Arguments.of(SettlementStatus.READY, "release", null),

                Arguments.of(SettlementStatus.FAILED, "markPaid", SettlementStatus.PAID),
                Arguments.of(SettlementStatus.FAILED, "markFailed", SettlementStatus.FAILED),
                Arguments.of(SettlementStatus.FAILED, "markManualRequired", SettlementStatus.MANUAL_REQUIRED),
                Arguments.of(SettlementStatus.FAILED, "hold", SettlementStatus.HOLD),
                Arguments.of(SettlementStatus.FAILED, "release", null),

                Arguments.of(SettlementStatus.MANUAL_REQUIRED, "markPaid", SettlementStatus.PAID),
                Arguments.of(SettlementStatus.MANUAL_REQUIRED, "markFailed", null),   // 자동 재시도로 되돌리지 않음
                Arguments.of(SettlementStatus.MANUAL_REQUIRED, "markManualRequired", SettlementStatus.MANUAL_REQUIRED),
                Arguments.of(SettlementStatus.MANUAL_REQUIRED, "hold", SettlementStatus.HOLD),
                Arguments.of(SettlementStatus.MANUAL_REQUIRED, "release", null),

                Arguments.of(SettlementStatus.PAID, "markPaid", null),
                Arguments.of(SettlementStatus.PAID, "markFailed", null),
                Arguments.of(SettlementStatus.PAID, "markManualRequired", null),
                Arguments.of(SettlementStatus.PAID, "hold", null),
                Arguments.of(SettlementStatus.PAID, "release", null),

                Arguments.of(SettlementStatus.HOLD, "markPaid", null),
                Arguments.of(SettlementStatus.HOLD, "markFailed", null),
                Arguments.of(SettlementStatus.HOLD, "markManualRequired", null),
                Arguments.of(SettlementStatus.HOLD, "hold", null),
                Arguments.of(SettlementStatus.HOLD, "release", SettlementStatus.READY));
    }

    @ParameterizedTest(name = "{0}에서 {1}() → {2}")
    @MethodSource("transitions")
    @DisplayName("상태 전이표대로만 바뀌고, 허용되지 않은 전이는 409로 막히며 상태가 그대로 남는다")
    void transition(SettlementStatus from, String action, SettlementStatus expected) {
        Settlement s = settlementIn(from);
        Runnable call = switch (action) {
            case "markPaid" -> () -> s.markPaid(1L, NOW);
            case "markFailed" -> () -> s.markFailed("실패", NOW, MAX_ATTEMPTS, RETRY_MINUTES);
            case "markManualRequired" -> () -> s.markManualRequired("수동 처리 필요");
            case "hold" -> () -> s.hold("보류");
            case "release" -> s::release;
            default -> throw new IllegalArgumentException(action);
        };

        if (expected == null) {
            DomainException e = assertThrows(DomainException.class, call::run);
            assertEquals("409-SETTLEMENT_STATE_INVALID", e.getResultCode());
            assertEquals(from, s.getStatus());
        } else {
            call.run();
            assertEquals(expected, s.getStatus());
        }
    }

    /** 재시도 횟수 경계: 이번 실패 전 실패 횟수 → 이번 실패 뒤 상태 (maxAttempts = 3) */
    static Stream<Arguments> retryBoundary() {
        return Stream.of(
                Arguments.of(0, SettlementStatus.FAILED),
                Arguments.of(1, SettlementStatus.FAILED),
                Arguments.of(2, SettlementStatus.MANUAL_REQUIRED));
    }

    @ParameterizedTest(name = "실패 {0}회인 정산서가 한 번 더 실패 → {1}")
    @MethodSource("retryBoundary")
    @DisplayName("retryCount >= maxAttempts 경계: 3번째 실패에서만 MANUAL_REQUIRED")
    void markFailed_boundary(int failuresBefore, SettlementStatus expected) {
        Settlement s = settlementIn(failuresBefore == 0 ? SettlementStatus.READY : SettlementStatus.FAILED);
        ReflectionTestUtils.setField(s, "retryCount", failuresBefore);

        s.markFailed("실패", NOW, MAX_ATTEMPTS, RETRY_MINUTES);

        assertEquals(expected, s.getStatus());
        assertEquals(failuresBefore + 1, s.getRetryCount());
        if (expected == SettlementStatus.MANUAL_REQUIRED) assertNull(s.getNextRetryAt());
        else assertEquals(NOW.plusMinutes(RETRY_MINUTES), s.getNextRetryAt());
    }

    /** 원하는 상태의 정산서 (후보 1건, 10,000원). 상태는 테스트를 위해 직접 넣는다 */
    private static Settlement settlementIn(SettlementStatus status) {
        Settlement s = ready();
        ReflectionTestUtils.setField(s, "status", status);
        if (status == SettlementStatus.FAILED) ReflectionTestUtils.setField(s, "nextRetryAt", NOW);
        if (status == SettlementStatus.HOLD) ReflectionTestUtils.setField(s, "holdReason", "보류");
        return s;
    }

    /** 후보 1건(10,000원)이 담긴 READY 정산서 */
    private static Settlement ready() {
        Settlement s = Settlement.create(7L, "2026-09", null);
        s.addCandidate(candidate(11L, 10_000L));
        return s;
    }

    private static SettlementCandidate candidate(Long orderItemId, long salePrice) {
        return SettlementCandidate.create(1L, orderItemId, 7L, salePrice, 10, LocalDateTime.of(2026, 9, 15, 12, 0));
    }
}