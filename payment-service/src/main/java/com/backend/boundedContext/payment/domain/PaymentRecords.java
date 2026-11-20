package com.backend.boundedContext.payment.domain;

import java.util.List;

// 결제(Payment.pay)가 남긴 기록: 원장과 홀딩. 호출한 쪽에서 모두 저장한다
public record PaymentRecords(List<WalletTransaction> transactions, WalletHolding holding) {
}
