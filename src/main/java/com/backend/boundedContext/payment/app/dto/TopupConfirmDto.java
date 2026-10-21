package com.backend.boundedContext.payment.app.dto;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;

/**
 * 충전 승인 결과
 * - status DONE: 예치금 반영 완료, balance는 반영 후 지갑 잔액
 * - status PROCESSING: 결과 확인 중 (토스 응답 없음 등). balance는 null, 스케줄러가 최종 결과를 반영
 */
public record TopupConfirmDto(
        Long topupId,
        String orderId,
        TopupStatus status,
        long amount,
        Long balance
) {
    public static TopupConfirmDto of(Topup topup, long balance) {
        return new TopupConfirmDto(topup.getId(), topup.getTossOrderId(), topup.getStatus(), topup.getAmount(), balance);
    }

    public static TopupConfirmDto processing(Long topupId, String orderId, long amount) {
        return new TopupConfirmDto(topupId, orderId, TopupStatus.PROCESSING, amount, null);
    }
}
