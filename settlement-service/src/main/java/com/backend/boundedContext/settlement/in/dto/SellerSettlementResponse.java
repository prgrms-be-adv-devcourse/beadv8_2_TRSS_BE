package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementSellerStatus;

import java.time.LocalDateTime;

/**
 * 판매자 본인 정산서 목록 1행
 * 실패·재처리 중인 정산서는 status=DELAYED("지급 지연")로만 보이고, 실패 사유·보류 사유·시도 횟수는 넣지 않는다
 */
public record SellerSettlementResponse(
        Long settlementId,
        String settlementMonth,
        SettlementSellerStatus status,
        String statusLabel,
        long totalSalePrice,
        long totalFee,
        long payoutAmount,
        int itemCount,
        LocalDateTime paidAt
) {
    public static SellerSettlementResponse from(Settlement s) {
        SettlementSellerStatus status = SettlementSellerStatus.of(s.getStatus());
        return new SellerSettlementResponse(s.getId(), s.getSettlementMonth(), status, status.getLabel(),
                s.getTotalSalePrice(), s.getTotalFee(), s.getPayoutAmount(), s.getItemCount(), s.getPaidAt());
    }
}