package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;

import java.time.LocalDateTime;

/** 정산서에 묶인 정산 후보 1건 (이월된 품목은 settlementMonth가 원래 정산월로 보인다) */
public record SettlementCandidateResponse(
        Long candidateId,
        Long orderId,
        Long orderItemId,
        long salePrice,
        int feeRate,
        long fee,
        long payoutAmount,
        LocalDateTime confirmedAt,
        String settlementMonth,
        SettlementCandidateStatus status
) {
    public static SettlementCandidateResponse from(SettlementCandidate c) {
        return new SettlementCandidateResponse(c.getId(), c.getOrderId(), c.getOrderItemId(), c.getSalePrice(),
                c.getFeeRate(), c.getFee(), c.getPayoutAmount(), c.getConfirmedAt(), c.getSettlementMonth(),
                c.getStatus());
    }
}