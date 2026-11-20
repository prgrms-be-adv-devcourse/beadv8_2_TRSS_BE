package com.backend.boundedContext.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 예치금 거래 유형. 유형마다 입금·차감 방향과 참조 대상(reference_type)이 정해져 있음
@Getter
@RequiredArgsConstructor
public enum WalletTransactionType {

    CHARGE(true, WalletTransactionReferenceType.TOPUP),                // 충전 (충전 모듈)
    CHARGE_CANCEL(false, WalletTransactionReferenceType.TOPUP),        // 미사용 충전 취소 (충전 모듈)
    CHARGE_CANCEL_RESTORE(true, WalletTransactionReferenceType.TOPUP), // 충전 취소가 PG에서 거절돼 되돌림 (충전 모듈)
    PAYMENT(false, WalletTransactionReferenceType.PAYMENT),            // 결제, 지갑 → 홀딩 (결제 모듈)
    PAYMENT_CANCEL(true, WalletTransactionReferenceType.PAYMENT),      // 결제 취소, 홀딩 → 구매자 지갑 (결제 모듈)
    SHIPPING_FEE(true, WalletTransactionReferenceType.PAYMENT),        // 결제 중 배송비, 구매자 → 플랫폼(관리자) 지갑 (결제 모듈)
    SHIPPING_FEE_CANCEL(false, WalletTransactionReferenceType.PAYMENT), // 결제 취소 시 배송비 반환, 플랫폼 지갑 → 구매자 (결제 모듈)
    COUPON_SUBSIDY(false, WalletTransactionReferenceType.PAYMENT),     // 결제 중 장바구니 쿠폰 지원금, 플랫폼(관리자) 지갑 → 홀딩 (결제 모듈)
    COUPON_SUBSIDY_CANCEL(true, WalletTransactionReferenceType.PAYMENT), // 결제 취소 시 쿠폰 지원금 반환, 홀딩 → 플랫폼 지갑 (결제 모듈)
    REFUND(true, WalletTransactionReferenceType.REFUND),               // 반품 승인 환불, 홀딩 → 구매자 지갑 (결제 모듈)
    SHIPPING_FEE_REFUND(false, WalletTransactionReferenceType.REFUND), // 반품 환불 시 처음 배송비 반환, 플랫폼 지갑 → 구매자 (단순 변심 제외, 결제 모듈)
    RETURN_PICKUP_FEE_PAYMENT(false, WalletTransactionReferenceType.REFUND), // 단순 변심 반품 회수 택배비 결제, 구매자 지갑 → 플랫폼 (결제 모듈)
    RETURN_PICKUP_FEE(true, WalletTransactionReferenceType.REFUND),    // 단순 변심 반품 회수 택배비 받음, 구매자 → 플랫폼 지갑 (결제 모듈)
    SETTLEMENT(true, WalletTransactionReferenceType.SETTLEMENT),       // 정산 지급, 홀딩 → 판매자 지갑 (정산 모듈)
    FEE(true, WalletTransactionReferenceType.SETTLEMENT);              // 정산 수수료, 홀딩 → 관리자 지갑 (정산 모듈)

    private final boolean deposit;
    private final WalletTransactionReferenceType referenceType;
}
