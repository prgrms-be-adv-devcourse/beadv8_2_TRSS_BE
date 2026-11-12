package com.backend.boundedContext.payment.app.dto;

import com.backend.boundedContext.payment.app.TopupCancelStartResult;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;

/**
 * 충전 취소 결과
 * - status CANCELED·DONE: 취소 완료 (충전 금액 전부가 취소되면 CANCELED, 일부를 썼으면 DONE). canceledAmount·balance는 취소 후 값
 * - status CANCELING: 결과 확인 중 (PG 응답 없음 등). canceledAmount·balance는 null, 스케줄러가 최종 결과를 반영
 */
public record TopupCancelDto(
        Long topupId,
        String orderId,
        TopupStatus status,
        long cancelAmount,     // 이번에 취소 요청한 금액
        Long canceledAmount,   // 이 충전 건에서 PG 취소가 확정된 금액 합계
        Long balance
) {
    public static TopupCancelDto of(Topup topup, long cancelAmount, long balance) {
        return new TopupCancelDto(topup.getId(), topup.getTossOrderId(), topup.getStatus(), cancelAmount, topup.getCanceledAmount(), balance);
    }

    public static TopupCancelDto canceling(TopupCancelStartResult start) {
        return new TopupCancelDto(start.topupId(), start.orderId(), TopupStatus.CANCELING, start.cancelAmount(), null, null);
    }
}
