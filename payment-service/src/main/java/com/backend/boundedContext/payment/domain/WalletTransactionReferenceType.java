package com.backend.boundedContext.payment.domain;

// 원장 거래가 어떤 건 때문에 생겼는지 (reference_id가 가리키는 대상)
public enum WalletTransactionReferenceType {

    TOPUP,       // topup (CHARGE, CHARGE_CANCEL)
    PAYMENT,     // payment (PAYMENT)
    REFUND,      // payment_refund (REFUND)
    SETTLEMENT   // settlement (SETTLEMENT, FEE)
}
