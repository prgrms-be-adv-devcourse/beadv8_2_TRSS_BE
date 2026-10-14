package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.global.exception.DomainException;

class TopupTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);

    private final Wallet wallet = Wallet.create(1L);

    @Test
    @DisplayName("충전 건을 만들면 TOPUP- 주문번호가 발급되고 READY, 남은 금액 0이다")
    void create() {
        Topup topup = Topup.create(1L, wallet, 20_000);

        assertThat(topup.getMemberId()).isEqualTo(1L);
        assertThat(topup.getWallet()).isSameAs(wallet);
        assertThat(topup.getTossOrderId()).startsWith("TOPUP-");
        assertThat(topup.getAmount()).isEqualTo(20_000);
        assertThat(topup.getRemainingAmount()).isZero();
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.READY);
    }

    @Test
    @DisplayName("충전 건마다 다른 주문번호가 발급된다")
    void create_uniqueOrderId() {
        Topup first = Topup.create(1L, wallet, 10_000);
        Topup second = Topup.create(1L, wallet, 10_000);

        assertThat(first.getTossOrderId()).isNotEqualTo(second.getTossOrderId());
    }

    @Test
    @DisplayName("READY 충전 건은 결제키를 저장하고 PROCESSING이 된다")
    void markProcessing() {
        Topup topup = Topup.create(1L, wallet, 10_000);

        topup.markProcessing("pk_1");

        assertThat(topup.getPaymentKey()).isEqualTo("pk_1");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("READY가 아닌 충전 건은 PROCESSING으로 바꿀 수 없다")
    void markProcessing_notReady() {
        Topup topup = Topup.create(1L, wallet, 10_000);
        topup.markProcessing("pk_1");

        assertThatThrownBy(() -> topup.markProcessing("pk_2"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("승인되면 DONE이 되고 충전 금액 전체가 남은 금액이 된다")
    void approve() {
        Topup topup = Topup.create(1L, wallet, 30_000);
        topup.markProcessing("pk_1");

        topup.approve(NOW);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getRemainingAmount()).isEqualTo(30_000);
        assertThat(topup.getApprovedAt()).isEqualTo(NOW);
        assertThat(topup.isCancelable()).isTrue();
    }

    @Test
    @DisplayName("PROCESSING이 아닌 충전 건은 승인할 수 없다")
    void approve_notProcessing() {
        Topup topup = Topup.create(1L, wallet, 10_000);

        assertThatThrownBy(() -> topup.approve(NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("READY·PROCESSING 충전 건은 실패 상태와 사유를 기록한다")
    void fail() {
        Topup ready = Topup.create(1L, wallet, 10_000);
        Topup processing = Topup.create(1L, wallet, 10_000);
        processing.markProcessing("pk_1");

        ready.fail(TopupStatus.FAILED_USER_CANCEL, "사용자가 결제를 취소했습니다.");
        processing.fail(TopupStatus.FAILED_PG_DECLINED, "카드사 승인 거절");

        assertThat(ready.getStatus()).isEqualTo(TopupStatus.FAILED_USER_CANCEL);
        assertThat(ready.getFailReason()).isEqualTo("사용자가 결제를 취소했습니다.");
        assertThat(processing.getStatus()).isEqualTo(TopupStatus.FAILED_PG_DECLINED);
        assertThat(processing.isCancelable()).isFalse();
    }

    @Test
    @DisplayName("이미 완료된 충전 건은 실패로 바꿀 수 없다")
    void fail_alreadyDone() {
        Topup topup = Topup.create(1L, wallet, 10_000);
        topup.markProcessing("pk_1");
        topup.approve(NOW);

        assertThatThrownBy(() -> topup.fail(TopupStatus.FAILED_SYSTEM_ERROR, "오류"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("실패가 아닌 상태로는 실패를 기록할 수 없다")
    void fail_notFailureStatus() {
        Topup topup = Topup.create(1L, wallet, 10_000);

        assertThatThrownBy(() -> topup.fail(TopupStatus.DONE, "오류"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제에 쓰면 남은 금액이 줄고, 일부라도 쓰면 토스 취소할 수 없다")
    void use() {
        Topup topup = doneTopup(20_000);

        topup.use(5_000);

        assertThat(topup.getRemainingAmount()).isEqualTo(15_000);
        assertThat(topup.isCancelable()).isFalse();
    }

    @Test
    @DisplayName("남은 금액보다 많이 쓸 수 없고, 남은 금액은 그대로다")
    void use_overRemaining() {
        Topup topup = doneTopup(10_000);

        assertThatThrownBy(() -> topup.use(10_001))
                .isInstanceOf(IllegalStateException.class);
        assertThat(topup.getRemainingAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("승인 완료되지 않은 충전 건은 쓸 수 없다")
    void use_notDone() {
        Topup topup = Topup.create(1L, wallet, 10_000);

        assertThatThrownBy(() -> topup.use(1_000))
                .isInstanceOf(IllegalStateException.class);
    }

    private Topup doneTopup(long amount) {
        Topup topup = Topup.create(1L, wallet, amount);
        topup.markProcessing("pk_" + amount);
        topup.approve(NOW);
        return topup;
    }
}
