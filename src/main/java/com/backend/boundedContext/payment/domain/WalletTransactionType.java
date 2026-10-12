package com.backend.boundedContext.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 예치금 거래 유형. 유형마다 입금·차감 방향과 참조 대상(reference_type)이 정해져 있음
@Getter
@RequiredArgsConstructor
public enum WalletTransactionType {

    CHARGE(true, WalletTransactionReferenceType.TOPUP),             // 충전 (충전 모듈)
    CHARGE_CANCEL(false, WalletTransactionReferenceType.TOPUP),     // 미사용 충전 취소 (충전 모듈)
    PAYMENT(false, WalletTransactionReferenceType.PAYMENT),         // 결제, 지갑 → 홀딩 (결제 모듈)
    REFUND(true, WalletTransactionReferenceType.REFUND),            // 반품 승인 환불, 홀딩 → 구매자 지갑 (결제 모듈)
    SETTLEMENT(true, WalletTransactionReferenceType.SETTLEMENT),    // 정산 지급, 홀딩 → 판매자 지갑 (정산 모듈)
    FEE(true, WalletTransactionReferenceType.SETTLEMENT);           // 정산 수수료, 홀딩 → 관리자 지갑 (정산 모듈)

    private final boolean deposit;
    private final WalletTransactionReferenceType referenceType;
}
