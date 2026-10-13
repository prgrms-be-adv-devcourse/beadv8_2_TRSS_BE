package com.backend.boundedContext.payment.app.dto;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;

// 충전 승인 결과. status가 DONE이면 balance는 반영 후 지갑 잔액
public record TopupConfirmDto(
        Long topupId,
        String orderId,
        TopupStatus status,
        long amount,
        long balance
) {
    public static TopupConfirmDto of(Topup topup, long balance) {
        return new TopupConfirmDto(topup.getId(), topup.getTossOrderId(), topup.getStatus(), topup.getAmount(), balance);
    }
}
