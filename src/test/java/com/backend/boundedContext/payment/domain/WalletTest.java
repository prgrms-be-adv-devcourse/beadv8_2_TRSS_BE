package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.backend.global.exception.DomainException;

class WalletTest {

    @Test
    @DisplayName("입금하면 잔액이 늘어난다")
    void deposit() {
        Wallet wallet = Wallet.create(1L);

        wallet.deposit(10_000);
        wallet.deposit(20_000);

        assertThat(wallet.getBalance()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("차감하면 잔액이 줄고, 잔액 전부를 차감할 수 있다")
    void withdraw() {
        Wallet wallet = Wallet.create(1L);
        wallet.deposit(30_000);

        wallet.withdraw(10_000);
        assertThat(wallet.getBalance()).isEqualTo(20_000);

        wallet.withdraw(20_000);
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    @DisplayName("잔액보다 많이 차감하면 402 INSUFFICIENT_BALANCE로 거절하고 잔액은 그대로다")
    void withdraw_insufficient() {
        Wallet wallet = Wallet.create(1L);
        wallet.deposit(10_000);

        assertThatThrownBy(() -> wallet.withdraw(10_001))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo("402-INSUFFICIENT_BALANCE");
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("0 이하 금액으로는 입금·차감할 수 없다")
    void nonPositiveAmount(long amount) {
        Wallet wallet = Wallet.create(1L);
        wallet.deposit(10_000);

        assertThatThrownBy(() -> wallet.deposit(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wallet.withdraw(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }
}
