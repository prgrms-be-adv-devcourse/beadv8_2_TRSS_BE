package com.backend.boundedContext.payment.support;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentRecords;
import com.backend.boundedContext.payment.domain.Wallet;

// 테스트 준비용으로 실제 결제(ready → pay)를 거친 결제 완료 건과 홀딩을 만든다
// 결제 금액만큼 먼저 충전해 두므로 지갑 잔액은 그대로다. 원장은 저장하지 않는다
// 결제를 저장하기 전에 pay()를 불러 원장 멱등 키에 결제 ID가 비어 있지만, 원장을 저장하지 않으므로 상관없다
public final class TestPayments {

    private TestPayments() {
    }

    // 결제 건은 records.holding().getPayment()로 꺼내 홀딩보다 먼저 저장한다
    public static PaymentRecords paid(Wallet wallet, Long orderId, String orderNumber, long amount,
                                      String idempotencyKey, LocalDateTime paidAt) {
        TestWalletCharger.charge(wallet, amount);
        Payment payment = Payment.ready(orderId, orderNumber, wallet, amount, 0, 0, idempotencyKey);
        return payment.pay(wallet, null, paidAt);
    }
}
