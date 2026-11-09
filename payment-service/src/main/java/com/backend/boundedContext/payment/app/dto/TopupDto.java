package com.backend.boundedContext.payment.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;

// 충전 목록 항목
public record TopupDto(
        Long topupId,
        String orderId,
        long amount,
        long remainingAmount,
        TopupStatus status,
        String failReason,  // 실패한 충전 건의 사유 (그 외 null)
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
    public static TopupDto from(Topup topup) {
        return new TopupDto(topup.getId(), topup.getTossOrderId(), topup.getAmount(), topup.getRemainingAmount(),
                topup.getStatus(), topup.getFailReason(), topup.getApprovedAt(), topup.getCreatedAt());
    }
}
