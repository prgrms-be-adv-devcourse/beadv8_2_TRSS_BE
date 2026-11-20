package com.backend.shared.payment.dto;

import java.util.List;

// 정산서 1건 = 판매자 1명 × 1개월. idempotencyKey는 SETTLEMENT-{settlementId}
public record SettlementTransferCommand(
        String idempotencyKey,
        Long settlementId,
        Long sellerMemberId,
        long payoutAmount,
        long feeAmount,
        List<SettledOrderAmountDto> orders
) {
}