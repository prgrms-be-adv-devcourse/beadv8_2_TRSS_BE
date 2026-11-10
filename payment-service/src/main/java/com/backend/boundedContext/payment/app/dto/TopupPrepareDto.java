package com.backend.boundedContext.payment.app.dto;

import com.backend.boundedContext.payment.domain.Topup;

// 충전 준비 결과. orderId·amount를 토스 결제창 요청에 그대로 사용
public record TopupPrepareDto(
        Long topupId,
        String orderId,
        long amount
) {
    public static TopupPrepareDto from(Topup topup) {
        return new TopupPrepareDto(topup.getId(), topup.getTossOrderId(), topup.getAmount());
    }
}
