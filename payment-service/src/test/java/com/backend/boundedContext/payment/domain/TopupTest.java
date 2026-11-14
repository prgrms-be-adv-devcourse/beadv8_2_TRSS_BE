package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.global.exception.DomainException;

class TopupTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);

    private final Wallet wallet = Wallet.create(1L);

    @Test
    @DisplayName("충전 건을 만들면 TOPUP- 주문번호가 발급되고 READY, 남은 금액 0, 결제할 PG가 기록된다")
    void create() {
        Topup topup = Topup.create(1L, wallet, 20_000, PgProvider.TOSS);

        assertThat(topup.getMemberId()).isEqualTo(1L);
        assertThat(topup.getWallet()).isSameAs(wallet);
        assertThat(topup.getTossOrderId()).startsWith("TOPUP-");
        assertThat(topup.getAmount()).isEqualTo(20_000);
        assertThat(topup.getRemainingAmount()).isZero();
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.READY);
        assertThat(topup.getPgProvider()).isEqualTo(PgProvider.TOSS);
    }

    @Test
    @DisplayName("충전 건마다 다른 주문번호가 발급된다")
    void create_uniqueOrderId() {
        Topup first = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);
        Topup second = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);

        assertThat(first.getTossOrderId()).isNotEqualTo(second.getTossOrderId());
    }

    @Test
    @DisplayName("READY 충전 건은 결제키를 저장하고 PROCESSING이 된다")
    void markProcessing() {
        Topup topup = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);

        topup.markProcessing("pk_1");

        assertThat(topup.getPaymentKey()).isEqualTo("pk_1");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("READY가 아닌 충전 건은 PROCESSING으로 바꿀 수 없다")
    void markProcessing_notReady() {
        Topup topup = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);
        topup.markProcessing("pk_1");

        assertThatThrownBy(() -> topup.markProcessing("pk_2"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("승인되면 DONE이 되고 충전 금액 전체가 남은 금액이 된다")
    void approve() {
        Topup topup = Topup.create(1L, wallet, 30_000, PgProvider.TOSS);
        topup.markProcessing("pk_1");

        topup.approve(NOW);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getRemainingAmount()).isEqualTo(30_000);
        assertThat(topup.getApprovedAt()).isEqualTo(NOW);
        assertThat(topup.cancelableAmount()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("PROCESSING이 아닌 충전 건은 승인할 수 없다")
    void approve_notProcessing() {
        Topup topup = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);

        assertThatThrownBy(() -> topup.approve(NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("READY·PROCESSING 충전 건은 실패 상태와 사유를 기록한다")
    void fail() {
        Topup ready = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);
        Topup processing = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);
        processing.markProcessing("pk_1");

        ready.fail(TopupStatus.FAILED_USER_CANCEL, "사용자가 결제를 취소했습니다.");
        processing.fail(TopupStatus.FAILED_PG_DECLINED, "카드사 승인 거절");

        assertThat(ready.getStatus()).isEqualTo(TopupStatus.FAILED_USER_CANCEL);
        assertThat(ready.getFailReason()).isEqualTo("사용자가 결제를 취소했습니다.");
        assertThat(processing.getStatus()).isEqualTo(TopupStatus.FAILED_PG_DECLINED);
        assertThat(processing.cancelableAmount()).isZero();
    }

    @Test
    @DisplayName("이미 완료된 충전 건은 실패로 바꿀 수 없다")
    void fail_alreadyDone() {
        Topup topup = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);
        topup.markProcessing("pk_1");
        topup.approve(NOW);

        assertThatThrownBy(() -> topup.fail(TopupStatus.FAILED_SYSTEM_ERROR, "오류"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("실패가 아닌 상태로는 실패를 기록할 수 없다")
    void fail_notFailureStatus() {
        Topup topup = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);

        assertThatThrownBy(() -> topup.fail(TopupStatus.DONE, "오류"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제에 쓰면 남은 금액이 줄고, 취소할 수 있는 금액도 남은 금액만큼으로 준다")
    void use() {
        Topup topup = doneTopup(20_000);

        topup.use(5_000);

        assertThat(topup.getRemainingAmount()).isEqualTo(15_000);
        assertThat(topup.cancelableAmount()).isEqualTo(15_000);
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
        Topup topup = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);

        assertThatThrownBy(() -> topup.use(1_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("취소를 시작하면 남은 금액 전부가 취소 중 금액으로 옮겨지고 CANCELING이 된다")
    void startCancel() {
        Topup topup = doneTopup(30_000);
        topup.use(10_000);

        topup.startCancel();

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CANCELING);
        assertThat(topup.getCancelingAmount()).isEqualTo(20_000);
        assertThat(topup.getRemainingAmount()).isZero();
        assertThat(topup.cancelableAmount()).isZero();
    }

    @Test
    @DisplayName("DONE이 아니거나 남은 금액이 없으면 409 TOPUP_NOT_CANCELABLE")
    void startCancel_notCancelable() {
        Topup processing = Topup.create(1L, wallet, 10_000, PgProvider.TOSS);
        processing.markProcessing("pk_1");
        Topup usedUp = doneTopup(10_000);
        usedUp.use(10_000);
        Topup canceling = doneTopup(20_000);
        canceling.startCancel();

        for (Topup topup : new Topup[] {processing, usedUp, canceling}) {
            assertThatThrownBy(topup::startCancel)
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("409-TOPUP_NOT_CANCELABLE");
        }
    }

    @Test
    @DisplayName("한 번도 안 쓴 충전 건의 취소가 확정되면 CANCELED가 된다")
    void completeCancel_full() {
        Topup topup = doneTopup(30_000);
        topup.startCancel();

        topup.completeCancel(NOW);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CANCELED);
        assertThat(topup.getCanceledAmount()).isEqualTo(30_000);
        assertThat(topup.getCancelingAmount()).isZero();
        assertThat(topup.getCanceledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("일부를 쓴 충전 건의 취소가 확정되면 취소 금액만 기록하고 DONE으로 남는다")
    void completeCancel_partial() {
        Topup topup = doneTopup(30_000);
        topup.use(10_000);
        topup.startCancel();

        topup.completeCancel(NOW);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getCanceledAmount()).isEqualTo(20_000);
        assertThat(topup.getRemainingAmount()).isZero();
        assertThat(topup.cancelableAmount()).isZero();
    }

    @Test
    @DisplayName("일부 취소 뒤 결제 취소로 되살아난 남은 금액을 다시 취소하면, 전부 취소돼 CANCELED가 된다")
    void cancelAgain_afterRestore() {
        Topup topup = doneTopup(30_000);
        topup.use(10_000);
        topup.startCancel();
        topup.completeCancel(NOW);

        topup.restore(10_000);
        assertThat(topup.cancelableAmount()).isEqualTo(10_000);
        topup.startCancel();
        topup.completeCancel(NOW);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CANCELED);
        assertThat(topup.getCanceledAmount()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("취소가 확정 실패하면 취소 중 금액을 남은 금액으로 되돌리고 DONE이 된다")
    void failCancel() {
        Topup topup = doneTopup(30_000);
        topup.use(10_000);
        topup.startCancel();

        topup.failCancel();

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getRemainingAmount()).isEqualTo(20_000);
        assertThat(topup.getCancelingAmount()).isZero();
        assertThat(topup.getCanceledAmount()).isZero();
    }

    @Test
    @DisplayName("취소 중에 결제 취소로 남은 금액이 늘어도, 취소 실패 시 그 금액을 덮어쓰지 않고 더한다")
    void failCancel_keepsRestoredDuringCanceling() {
        Topup topup = doneTopup(30_000);
        topup.use(10_000);
        topup.startCancel();

        topup.restore(10_000);
        topup.failCancel();

        assertThat(topup.getRemainingAmount()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("취소 중에 결제 취소로 남은 금액이 늘고 취소가 성공하면, 늘어난 금액은 남은 금액으로 DONE에 남는다")
    void completeCancel_keepsRestoredDuringCanceling() {
        Topup topup = doneTopup(30_000);
        topup.use(10_000);
        topup.startCancel();

        topup.restore(10_000);
        topup.completeCancel(NOW);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getCanceledAmount()).isEqualTo(20_000);
        assertThat(topup.getRemainingAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("CANCELING이 아니면 취소 확정·실패를 반영할 수 없다")
    void completeOrFailCancel_notCanceling() {
        Topup topup = doneTopup(10_000);

        assertThatThrownBy(() -> topup.completeCancel(NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThatThrownBy(topup::failCancel)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("취소된 금액과 취소 중 금액을 합쳐 충전 금액보다 많이 복원할 수 없다")
    void restore_overAmountWithCanceled() {
        Topup topup = doneTopup(30_000);
        topup.use(10_000);
        topup.startCancel();
        topup.completeCancel(NOW);

        assertThatThrownBy(() -> topup.restore(10_001))
                .isInstanceOf(IllegalStateException.class);
        assertThat(topup.getRemainingAmount()).isZero();
    }

    @Test
    @DisplayName("취소 멱등 키는 취소 회차마다 다르다 (성공한 뒤 다시 취소해도 회차 번호가 늘어남)")
    void cancelIdempotencyKey_perAttempt() {
        Topup topup = doneTopup(30_000);
        ReflectionTestUtils.setField(topup, "id", 7L);
        topup.use(10_000);

        topup.startCancel();
        String first = topup.cancelIdempotencyKey();
        topup.completeCancel(NOW);
        topup.restore(10_000);
        topup.startCancel();
        String second = topup.cancelIdempotencyKey();

        assertThat(first).isEqualTo("TOPUP-7-CANCEL-1");
        assertThat(second).isEqualTo("TOPUP-7-CANCEL-2");
        assertThat(topup.cancelRestoreIdempotencyKey()).isEqualTo("TOPUP-7-CANCEL-2-RESTORE");
    }

    @Test
    @DisplayName("취소가 거절돼 되돌린 뒤 다시 취소하면 다른 멱등 키를 쓴다 (원장 키·PG 멱등 키가 겹치지 않음)")
    void cancelIdempotencyKey_afterFail() {
        Topup topup = doneTopup(30_000);
        ReflectionTestUtils.setField(topup, "id", 7L);

        topup.startCancel();
        String first = topup.cancelIdempotencyKey();
        topup.failCancel();
        topup.startCancel();

        assertThat(first).isEqualTo("TOPUP-7-CANCEL-1");
        assertThat(topup.cancelIdempotencyKey()).isEqualTo("TOPUP-7-CANCEL-2");
        assertThat(topup.getCancelAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("취소 결과는 지금 CANCELING인 회차에만 반영한다 (끝난 회차의 결과로 다음 회차를 바꾸지 않음)")
    void isCancelingAttempt() {
        Topup topup = doneTopup(30_000);
        topup.startCancel();
        assertThat(topup.isCancelingAttempt(1)).isTrue();

        topup.failCancel();
        topup.startCancel();

        assertThat(topup.isCancelingAttempt(1)).isFalse();
        assertThat(topup.isCancelingAttempt(2)).isTrue();
        topup.completeCancel(NOW);
        assertThat(topup.isCancelingAttempt(2)).isFalse();
    }

    @Test
    @DisplayName("허용하지 않은 결제 수단으로 승인되면 예치금에 반영하지 않고 METHOD_CANCELING이 되며 취소 회차가 1이 된다")
    void startMethodCancel() {
        Topup topup = Topup.create(1L, wallet, 30_000, PgProvider.TOSS);
        topup.markProcessing("pk_1");

        topup.startMethodCancel();

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.METHOD_CANCELING);
        assertThat(topup.getCancelAttempts()).isEqualTo(1);
        assertThat(topup.getRemainingAmount()).isZero();
        assertThat(topup.cancelableAmount()).isZero();
    }

    @Test
    @DisplayName("PROCESSING이 아니면 허용하지 않은 결제 수단 취소를 시작할 수 없다")
    void startMethodCancel_notProcessing() {
        Topup topup = doneTopup(30_000);

        assertThatThrownBy(topup::startMethodCancel)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    @Test
    @DisplayName("다시 요청하면 취소 회차만 늘어 새 멱등 키를 쓴다 (충전 취소 키와 겹치지 않음)")
    void retryMethodCancel() {
        Topup topup = Topup.create(1L, wallet, 30_000, PgProvider.TOSS);
        ReflectionTestUtils.setField(topup, "id", 7L);
        topup.markProcessing("pk_1");
        topup.startMethodCancel();
        String first = topup.methodCancelIdempotencyKey();

        topup.retryMethodCancel();

        assertThat(first).isEqualTo("TOPUP-7-METHOD-CANCEL-1");
        assertThat(topup.methodCancelIdempotencyKey()).isEqualTo("TOPUP-7-METHOD-CANCEL-2");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.METHOD_CANCELING);
        assertThat(topup.isMethodCancelingAttempt(1)).isFalse();
        assertThat(topup.isMethodCancelingAttempt(2)).isTrue();
    }

    @Test
    @DisplayName("전액 취소가 확정되면 FAILED_METHOD_NOT_ALLOWED가 되고 취소된 금액·사유가 남는다")
    void completeMethodCancel() {
        Topup topup = Topup.create(1L, wallet, 30_000, PgProvider.TOSS);
        topup.markProcessing("pk_1");
        topup.startMethodCancel();

        topup.completeMethodCancel("허용하지 않은 결제 수단(OTHER)이라 PG에서 취소했습니다.", NOW);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_METHOD_NOT_ALLOWED);
        assertThat(topup.getCanceledAmount()).isEqualTo(30_000);
        assertThat(topup.getCanceledAt()).isEqualTo(NOW);
        assertThat(topup.getFailReason()).contains("OTHER");
        assertThat(topup.isMethodCancelingAttempt(1)).isFalse();
    }

    @Test
    @DisplayName("METHOD_CANCELING이 아니면 다시 요청·확정할 수 없다")
    void retryOrCompleteMethodCancel_notMethodCanceling() {
        Topup topup = doneTopup(30_000);

        assertThatThrownBy(topup::retryMethodCancel)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThatThrownBy(() -> topup.completeMethodCancel("사유", NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
    }

    private Topup doneTopup(long amount) {
        Topup topup = Topup.create(1L, wallet, amount, PgProvider.TOSS);
        topup.markProcessing("pk_" + amount);
        topup.approve(NOW);
        return topup;
    }
}
