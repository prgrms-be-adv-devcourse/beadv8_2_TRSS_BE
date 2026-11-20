package com.backend.shared.payment.dto;

import java.time.LocalDateTime;

public record TransferResultDto(
        String idempotencyKey,
        Long walletTransactionId, // 판매자 지급(SETTLEMENT) 원장의 ID
        long payoutAmount,
        long feeAmount,
        LocalDateTime transferredAt
) {
}
