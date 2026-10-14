package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;

import java.time.LocalDateTime;

/** 관리자 정산서 목록 1행: 금액·상태와 실패 사유·시도 횟수·다음 재시도 일시 */
public record SettlementResponse(
        Long settlementId,
        Long sellerId,
        String settlementMonth,
        long totalSalePrice,
        long totalFee,
        long payoutAmount,
        int itemCount,
        SettlementStatus status,
        int retryCount,
        LocalDateTime nextRetryAt,
        String lastFailReason,
        String holdReason,
        LocalDateTime paidAt,
        LocalDateTime createdAt
) {
    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(s.getId(), s.getSellerId(), s.getSettlementMonth(), s.getTotalSalePrice(),
                s.getTotalFee(), s.getPayoutAmount(), s.getItemCount(), s.getStatus(), s.getRetryCount(),
                s.getNextRetryAt(), s.getLastFailReason(), s.getHoldReason(), s.getPaidAt(), s.getCreatedAt());
    }
}