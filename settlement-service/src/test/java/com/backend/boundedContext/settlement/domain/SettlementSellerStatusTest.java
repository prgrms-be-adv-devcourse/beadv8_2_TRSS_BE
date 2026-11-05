package com.backend.boundedContext.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 판매자 화면 상태 변환과 정산 예정일 계산 */
class SettlementSellerStatusTest {

    private final SettlementPolicy policy = new SettlementPolicy(10, 10, 3, 10, 100, 50);

    @Test
    @DisplayName("FAILED·MANUAL_REQUIRED는 판매자에게 모두 지급 지연(DELAYED)으로만 보인다")
    void of_delayed() {
        assertEquals(SettlementSellerStatus.DELAYED, SettlementSellerStatus.of(SettlementStatus.FAILED));
        assertEquals(SettlementSellerStatus.DELAYED, SettlementSellerStatus.of(SettlementStatus.MANUAL_REQUIRED));
        assertEquals("지급 지연", SettlementSellerStatus.DELAYED.getLabel());
        assertEquals(SettlementSellerStatus.SCHEDULED, SettlementSellerStatus.of(SettlementStatus.READY));
        assertEquals(SettlementSellerStatus.PAID, SettlementSellerStatus.of(SettlementStatus.PAID));
        assertEquals(SettlementSellerStatus.ON_HOLD, SettlementSellerStatus.of(SettlementStatus.HOLD));
    }

    @Test
    @DisplayName("정산서 상태는 빠짐없이 판매자 상태 하나에만 속한다")
    void everyStatusBelongsToExactlyOne() {
        for (SettlementStatus status : SettlementStatus.values()) {
            long owners = Arrays.stream(SettlementSellerStatus.values())
                    .filter(s -> s.getStatuses().contains(status))
                    .count();
            assertEquals(1, owners, status.name());
        }
    }

    @Test
    @DisplayName("정산 예정일: 정산월 다음 달 정산일, 그 날이 지났으면(이월) 다가올 정산일")
    void expectedPayDate() {
        // 10월 5일 기준: 9월분은 10월 10일, 10월분은 11월 10일
        LocalDateTime oct5 = LocalDateTime.of(2026, 10, 5, 12, 0);
        assertEquals(LocalDate.of(2026, 10, 10), policy.expectedPayDate("2026-09", oct5));
        assertEquals(LocalDate.of(2026, 11, 10), policy.expectedPayDate("2026-10", oct5));

        // 10월 15일 기준: 10월 10일 Job이 이미 지나간 9월분(늦게 생긴 후보)은 11월 10일로 이월
        LocalDateTime oct15 = LocalDateTime.of(2026, 10, 15, 12, 0);
        assertEquals(LocalDate.of(2026, 11, 10), policy.expectedPayDate("2026-09", oct15));

        // 정산일 당일은 00:00에 이미 Job이 돌았으므로 다음 달 정산일
        LocalDateTime oct10 = LocalDateTime.of(2026, 10, 10, 9, 0);
        assertEquals(LocalDate.of(2026, 11, 10), policy.expectedPayDate("2026-09", oct10));

        // 연도 경계: 12월분은 다음 해 1월 10일
        assertEquals(LocalDate.of(2027, 1, 10), policy.expectedPayDate("2026-12", LocalDateTime.of(2026, 12, 20, 0, 0)));
    }
}