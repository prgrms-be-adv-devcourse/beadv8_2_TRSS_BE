package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;

import java.time.LocalDateTime;

/**
 * 판매자 정산서 상세의 품목별 내역 1건 (정산서에 묶인 정산 후보)
 * settlementMonth는 품목의 원래 정산월(구매 확정월)이고, 정산서의 정산월과 다르면 carriedOver=true(이월된 품목)
 */
public record SellerSettlementItemResponse(
        Long orderId,
        Long orderItemId,
        long salePrice,
        int feeRate,
        long fee,
        long payoutAmount,
        LocalDateTime confirmedAt,
        String settlementMonth,
        boolean carriedOver
) {
    public static SellerSettlementItemResponse of(SettlementCandidate c, String settlementMonthOfStatement) {
        return new SellerSettlementItemResponse(c.getOrderId(), c.getOrderItemId(), c.getSalePrice(), c.getFeeRate(),
                c.getFee(), c.getPayoutAmount(), c.getConfirmedAt(), c.getSettlementMonth(),
                !c.getSettlementMonth().equals(settlementMonthOfStatement));
    }
}