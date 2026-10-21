package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.global.exception.DomainException;

class WalletHoldingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 10, 0);

    private final Wallet wallet = Wallet.create(3L);
    private final Payment payment = Payment.paid(10L, "ORD-TEST-10", wallet, 25_000, "PAY-10-a", NOW);

    @Test
    @DisplayName("결제 완료 건의 주문·지갑·금액으로 HELD 홀딩을 만든다")
    void hold() {
        WalletHolding holding = WalletHolding.hold(payment, NOW);

        assertThat(holding.getOrderId()).isEqualTo(10L);
        assertThat(holding.getPayment()).isSameAs(payment);
        assertThat(holding.getWallet()).isSameAs(wallet);
        assertThat(holding.getAmount()).isEqualTo(25_000);
        assertThat(holding.getSettledAmount()).isZero();
        assertThat(holding.remainingAmount()).isEqualTo(25_000);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.HELD);
        assertThat(holding.getHeldAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("실패한 결제 건은 홀딩할 수 없다")
    void hold_notPaid() {
        Payment failed = Payment.failed(10L, "ORD-TEST-10", wallet, 25_000, "PAY-10-a",
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족");

        assertThatThrownBy(() -> WalletHolding.hold(failed, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("일부만 정산하면 정산 누적 금액만 늘고 HELD로 남는다")
    void settle_partial() {
        WalletHolding holding = WalletHolding.hold(payment, NOW);

        holding.settle(10_000, NOW);

        assertThat(holding.getSettledAmount()).isEqualTo(10_000);
        assertThat(holding.remainingAmount()).isEqualTo(15_000);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.HELD);
        assertThat(holding.getSettledAt()).isNull();
    }

    @Test
    @DisplayName("정산 누적 금액이 홀딩 금액과 같아지면 SETTLED가 된다")
    void settle_full() {
        WalletHolding holding = WalletHolding.hold(payment, NOW);
        holding.settle(10_000, NOW);

        holding.settle(15_000, NOW.plusDays(1));

        assertThat(holding.getSettledAmount()).isEqualTo(25_000);
        assertThat(holding.remainingAmount()).isZero();
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.SETTLED);
        assertThat(holding.getSettledAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    @DisplayName("남은 금액보다 많이 정산하면 409 HOLDING_INSUFFICIENT이고 금액은 그대로다")
    void settle_insufficient() {
        WalletHolding holding = WalletHolding.hold(payment, NOW);
        holding.settle(20_000, NOW);

        assertThatThrownBy(() -> holding.settle(5_001, NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-HOLDING_INSUFFICIENT");
        assertThat(holding.getSettledAmount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("SETTLED 홀딩은 다시 정산할 수 없다")
    void settle_notHeld() {
        WalletHolding holding = WalletHolding.hold(payment, NOW);
        holding.settle(25_000, NOW);

        assertThatThrownBy(() -> holding.settle(1, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정산 금액은 0보다 커야 한다")
    void settle_nonPositive() {
        WalletHolding holding = WalletHolding.hold(payment, NOW);

        assertThatThrownBy(() -> holding.settle(0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
