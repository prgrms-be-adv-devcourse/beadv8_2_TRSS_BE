package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;

import java.time.LocalDateTime;

/**
 * 관리자 정산서 목록 1행: 금액·상태와 실패 사유·시도 횟수·다음 재시도 일시
 * businessName: 판매자 상호명. 회원 컨텍스트에 판매자가 없으면 null (미리보기 응답과 같은 규칙)
 */
public record SettlementResponse(
        Long settlementId,
        Long sellerId,
        String businessName,
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
    public static SettlementResponse of(Settlement s, String businessName) {
        return new SettlementResponse(s.getId(), s.getSellerId(), businessName, s.getSettlementMonth(), s.getTotalSalePrice(),
                s.getTotalFee(), s.getPayoutAmount(), s.getItemCount(), s.getStatus(), s.getRetryCount(),
                s.getNextRetryAt(), s.getLastFailReason(), s.getHoldReason(), s.getPaidAt(), s.getCreatedAt());
    }
}