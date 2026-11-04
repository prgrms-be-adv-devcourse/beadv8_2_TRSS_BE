package com.backend.boundedContext.payment.app.dto;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;

// 충전 실패 기록 결과
public record TopupFailDto(
        Long topupId,
        TopupStatus status,
        String failReason
) {
    public static TopupFailDto from(Topup topup) {
        return new TopupFailDto(topup.getId(), topup.getStatus(), topup.getFailReason());
    }
}
