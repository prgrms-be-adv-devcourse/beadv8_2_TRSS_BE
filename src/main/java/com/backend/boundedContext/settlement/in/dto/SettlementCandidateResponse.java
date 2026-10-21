package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;

import java.time.LocalDateTime;

/**
 * 관리자 화면의 정산 후보 1건 (정산서 상세의 묶인 후보, 후보 목록, 후보 제외 결과)
 * 이월된 품목은 settlementMonth가 원래 정산월로 보인다
 * 후보 목록·제외 화면용 sellerId·settlementId·excludedReason
 */
public record SettlementCandidateResponse(
        Long candidateId,
        Long sellerId,
        Long settlementId,
        Long orderId,
        Long orderItemId,
        long salePrice,
        int feeRate,
        long fee,
        long payoutAmount,
        LocalDateTime confirmedAt,
        String settlementMonth,
        SettlementCandidateStatus status,
        String excludedReason
) {
    public static SettlementCandidateResponse from(SettlementCandidate c) {
        return new SettlementCandidateResponse(c.getId(), c.getSellerId(), c.getSettlementId(), c.getOrderId(),
                c.getOrderItemId(), c.getSalePrice(), c.getFeeRate(), c.getFee(), c.getPayoutAmount(),
                c.getConfirmedAt(), c.getSettlementMonth(), c.getStatus(), c.getExcludedReason());
    }
}