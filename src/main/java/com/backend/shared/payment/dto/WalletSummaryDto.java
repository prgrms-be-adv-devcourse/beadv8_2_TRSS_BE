package com.backend.shared.payment.dto;

// 지갑 잔액 요약 (balance: 사용 가능 금액, heldAmount: 결제 후 홀딩 중인 금액)
public record WalletSummaryDto(
        Long memberId,
        long balance,
        long heldAmount
) {}
