package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 10, 0);

    private final Wallet wallet = Wallet.create(3L);

    @Test
    @DisplayName("결제 성공 건은 PAID이고 결제 일시가 남으며, 회원 ID는 지갑에서 가져온다")
    void paid() {
        Payment payment = Payment.paid(10L, wallet, 25_000, "PAY-10-a", NOW);

        assertThat(payment.getOrderId()).isEqualTo(10L);
        assertThat(payment.getMemberId()).isEqualTo(3L);
        assertThat(payment.getWallet()).isSameAs(wallet);
        assertThat(payment.getAmount()).isEqualTo(25_000);
        assertThat(payment.getIdempotencyKey()).isEqualTo("PAY-10-a");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(payment.isPaid()).isTrue();
    }

    @Test
    @DisplayName("결제 실패 건은 FAILED_* 상태와 실패 사유가 남고 결제 일시는 없다")
    void failed() {
        Payment payment = Payment.failed(10L, wallet, 25_000, "PAY-10-a",
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(payment.getFailReason()).isEqualTo("잔액 부족");
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.isPaid()).isFalse();
    }

    @Test
    @DisplayName("실패가 아닌 상태로는 실패 건을 만들 수 없다")
    void failed_notFailureStatus() {
        assertThatThrownBy(() -> Payment.failed(10L, wallet, 25_000, "PAY-10-a", PaymentStatus.PAID, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 금액은 0보다 커야 한다")
    void paid_nonPositiveAmount() {
        assertThatThrownBy(() -> Payment.paid(10L, wallet, 0, "PAY-10-a", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FAILED_로 시작하는 상태만 실패 상태다")
    void statusIsFailure() {
        assertThat(PaymentStatus.FAILED_INSUFFICIENT_BALANCE.isFailure()).isTrue();
        assertThat(PaymentStatus.FAILED_ORDER_EXPIRED.isFailure()).isTrue();
        assertThat(PaymentStatus.FAILED_SYSTEM_ERROR.isFailure()).isTrue();
        assertThat(PaymentStatus.PAID.isFailure()).isFalse();
        assertThat(PaymentStatus.REFUNDED.isFailure()).isFalse();
    }
}
