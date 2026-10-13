package com.backend.shared.payment.dto;

// shortage: 잔액 부족일 때 모자란 금액, chargeAmount: 1만 원 단위로 올린 충전 권장 금액
public record PayResultDto(
        Long paymentId,
        PayResultStatus status,
        long shortage,
        long chargeAmount
) {
    public boolean isPaid() { return status == PayResultStatus.PAID; }
}
