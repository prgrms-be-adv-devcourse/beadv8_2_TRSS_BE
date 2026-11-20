package com.backend.boundedContext.payment.support;

import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransactionType;

// 테스트 준비용으로 지갑에 잔액을 충전해 둔다. 잔액은 wallet.change()로만 바뀌므로 충전(CHARGE)으로 넣고, 만든 원장은 저장하지 않는다
public final class TestWalletCharger {

    private TestWalletCharger() {
    }

    public static void charge(Wallet wallet, long amount) {
        wallet.change(WalletTransactionType.CHARGE, amount, 0L, "TEST-CHARGE");
    }
}
