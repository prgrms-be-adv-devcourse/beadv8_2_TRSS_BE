package com.backend.shared.payment.dto;

import java.time.LocalDateTime;

// shortage: 잔액 부족일 때 모자란 금액, chargeAmount: 1만 원 단위로 올린 충전 권장 금액
// paidAt: payment가 기록한 결제 완료 시각. PAID일 때만 있고 그 밖의 상태는 null (paidAt을 보내지 않는 이전 버전 응답도 null)
public record PayResultDto(
        Long paymentId,
        PayResultStatus status,
        long shortage,
        long chargeAmount,
        LocalDateTime paidAt
) {
    // paidAt 없는 결과 (PAID가 아닌 결과·테스트)
    public PayResultDto(Long paymentId, PayResultStatus status, long shortage, long chargeAmount) {
        this(paymentId, status, shortage, chargeAmount, null);
    }

    public boolean isPaid() { return status == PayResultStatus.PAID; }
}
