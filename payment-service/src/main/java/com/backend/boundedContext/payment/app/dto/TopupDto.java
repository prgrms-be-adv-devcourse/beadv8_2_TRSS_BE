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
        long cancelableAmount,  // 지금 취소할 수 있는 금액 (DONE이면 남은 금액, 그 외 0)
        long canceledAmount,    // PG 취소가 확정된 금액 합계
        TopupStatus status,
        String failReason,  // 실패한 충전 건의 사유 (그 외 null)
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
    public static TopupDto from(Topup topup) {
        return new TopupDto(topup.getId(), topup.getTossOrderId(), topup.getAmount(), topup.getRemainingAmount(),
                topup.cancelableAmount(), topup.getCanceledAmount(), topup.getStatus(), topup.getFailReason(), topup.getApprovedAt(), topup.getCreatedAt());
    }
}
