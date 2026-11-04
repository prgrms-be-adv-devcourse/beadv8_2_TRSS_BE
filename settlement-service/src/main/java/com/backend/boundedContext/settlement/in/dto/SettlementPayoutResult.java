package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;

import java.time.LocalDateTime;

/**
 * 지급 1건의 결과
 *
 * 배치·스케줄러는 건수 집계에, 관리자 재처리 API는 응답에 쓴다
 * processed가 false면 지급 가능한 상태가 아니어서 건너뛰었다는 뜻이다(실패 횟수로는 세지 않음)
 */
public record SettlementPayoutResult(
        Long settlementId,
        boolean processed,
        SettlementStatus status,
        int attemptNo,
        long payoutAmount,
        long totalFee,
        int retryCount,
        String lastFailReason,
        LocalDateTime nextRetryAt,
        LocalDateTime paidAt
) {
    public static SettlementPayoutResult of(Settlement s, int attemptNo, boolean processed) {
        return new SettlementPayoutResult(s.getId(), processed, s.getStatus(), attemptNo, s.getPayoutAmount(),
                s.getTotalFee(), s.getRetryCount(), s.getLastFailReason(), s.getNextRetryAt(), s.getPaidAt());
    }

    public static SettlementPayoutResult skipped(Settlement s) {
        return of(s, s.nextAttemptNo(), false);
    }

    public boolean isPaid() {
        return processed && status == SettlementStatus.PAID;
    }
}