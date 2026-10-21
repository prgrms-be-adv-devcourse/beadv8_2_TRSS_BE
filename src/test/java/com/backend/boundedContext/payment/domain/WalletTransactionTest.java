package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.global.exception.DomainException;

class WalletTransactionTest {

    @Test
    @DisplayName("입금 유형이면 지갑 잔액이 늘고, + 금액과 거래 후 잔액을 기록한다")
    void record_deposit() {
        Wallet wallet = Wallet.create(1L);
        wallet.deposit(5_000);

        WalletTransaction transaction = WalletTransaction.record(wallet, WalletTransactionType.CHARGE, 20_000, 7L, "TOPUP-7-CHARGE");

        assertThat(wallet.getBalance()).isEqualTo(25_000);
        assertThat(transaction.getWallet()).isSameAs(wallet);
        assertThat(transaction.getType()).isEqualTo(WalletTransactionType.CHARGE);
        assertThat(transaction.getAmount()).isEqualTo(20_000);
        assertThat(transaction.getBalanceAfter()).isEqualTo(25_000);
        assertThat(transaction.getReferenceType()).isEqualTo(WalletTransactionReferenceType.TOPUP);
        assertThat(transaction.getReferenceId()).isEqualTo(7L);
        assertThat(transaction.getIdempotencyKey()).isEqualTo("TOPUP-7-CHARGE");
    }

    @Test
    @DisplayName("차감 유형이면 지갑 잔액이 줄고, - 금액과 거래 후 잔액을 기록한다")
    void record_withdraw() {
        Wallet wallet = Wallet.create(1L);
        wallet.deposit(30_000);

        WalletTransaction transaction = WalletTransaction.record(wallet, WalletTransactionType.PAYMENT, 12_000, 3L, "PAYMENT-3");

        assertThat(wallet.getBalance()).isEqualTo(18_000);
        assertThat(transaction.getAmount()).isEqualTo(-12_000);
        assertThat(transaction.getBalanceAfter()).isEqualTo(18_000);
        assertThat(transaction.getReferenceType()).isEqualTo(WalletTransactionReferenceType.PAYMENT);
    }

    @Test
    @DisplayName("차감할 잔액이 부족하면 402 INSUFFICIENT_BALANCE로 원장을 만들지 않고 잔액도 그대로다")
    void record_insufficient() {
        Wallet wallet = Wallet.create(1L);
        wallet.deposit(10_000);

        assertThatThrownBy(() -> WalletTransaction.record(wallet, WalletTransactionType.CHARGE_CANCEL, 20_000, 7L, "TOPUP-7-CHARGE_CANCEL"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo("402-INSUFFICIENT_BALANCE");
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("유형별 입금·차감 방향이 정책과 같다")
    void typeDirection() {
        assertThat(WalletTransactionType.CHARGE.isDeposit()).isTrue();
        assertThat(WalletTransactionType.CHARGE_CANCEL.isDeposit()).isFalse();
        assertThat(WalletTransactionType.PAYMENT.isDeposit()).isFalse();
        assertThat(WalletTransactionType.REFUND.isDeposit()).isTrue();
        assertThat(WalletTransactionType.SETTLEMENT.isDeposit()).isTrue();
        assertThat(WalletTransactionType.FEE.isDeposit()).isTrue();
    }

    @Test
    @DisplayName("유형별 참조 대상이 정해져 있다")
    void typeReference() {
        assertThat(WalletTransactionType.CHARGE.getReferenceType()).isEqualTo(WalletTransactionReferenceType.TOPUP);
        assertThat(WalletTransactionType.CHARGE_CANCEL.getReferenceType()).isEqualTo(WalletTransactionReferenceType.TOPUP);
        assertThat(WalletTransactionType.PAYMENT.getReferenceType()).isEqualTo(WalletTransactionReferenceType.PAYMENT);
        assertThat(WalletTransactionType.REFUND.getReferenceType()).isEqualTo(WalletTransactionReferenceType.REFUND);
        assertThat(WalletTransactionType.SETTLEMENT.getReferenceType()).isEqualTo(WalletTransactionReferenceType.SETTLEMENT);
        assertThat(WalletTransactionType.FEE.getReferenceType()).isEqualTo(WalletTransactionReferenceType.SETTLEMENT);
    }
}
