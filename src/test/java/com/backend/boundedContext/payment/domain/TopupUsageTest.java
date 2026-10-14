package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TopupUsageTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);

    private final Wallet wallet = Wallet.create(3L);
    private final Payment payment = Payment.paid(10L, wallet, 25_000, "PAY-10-a", NOW);

    @Test
    @DisplayName("차감 내역을 만들면 충전 건의 남은 금액이 그만큼 준다")
    void use() {
        Topup topup = doneTopup(30_000);

        TopupUsage usage = TopupUsage.use(topup, payment, 25_000);

        assertThat(usage.getTopup()).isSameAs(topup);
        assertThat(usage.getPayment()).isSameAs(payment);
        assertThat(usage.getAmount()).isEqualTo(25_000);
        assertThat(topup.getRemainingAmount()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("충전 건의 남은 금액보다 많이 차감할 수 없다")
    void use_overRemaining() {
        Topup topup = doneTopup(10_000);

        assertThatThrownBy(() -> TopupUsage.use(topup, payment, 25_000))
                .isInstanceOf(IllegalStateException.class);
        assertThat(topup.getRemainingAmount()).isEqualTo(10_000);
    }

    private Topup doneTopup(long amount) {
        Topup topup = Topup.create(3L, wallet, amount);
        topup.markProcessing("pk_" + amount);
        topup.approve(NOW);
        return topup;
    }
}
