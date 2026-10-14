package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.global.exception.DomainException;

class PaymentTopupConfirmProcessorTest {

    private static final Long MEMBER_ID = 3L;

    private static final Long TOPUP_ID = 7L;
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 10, 13, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletTransactionRepository walletTransactionRepository = mock(WalletTransactionRepository.class);
    private final PaymentTopupConfirmProcessor processor =
            new PaymentTopupConfirmProcessor(topupRepository, walletRepository, walletTransactionRepository);

    @Test
    @DisplayName("승인 시작: READY 충전 건을 잠금 조회해 PROCESSING으로 바꾸고 결제키를 저장한다")
    void start_success() {
        Topup topup = readyTopup(20_000);

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 20_000);

        assertThat(result.isStarted()).isTrue();
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
        assertThat(topup.getPaymentKey()).isEqualTo("pk_1");
    }

    @Test
    @DisplayName("승인 시작: 금액이 다르면 FAILED_AMOUNT_MISMATCH로 기록하고 예외 대신 결과로 돌려준다")
    void start_amountMismatch() {
        Topup topup = readyTopup(20_000);

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 10_000);

        assertThat(result.outcome()).isEqualTo(TopupConfirmStartResult.Outcome.AMOUNT_MISMATCH);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_AMOUNT_MISMATCH);
        assertThat(topup.getFailReason()).contains("20000", "10000");
        assertThat(topup.getPaymentKey()).isNull();
    }

    @Test
    @DisplayName("승인 시작: 충전 건이 없거나 남의 충전 건이면 404 TOPUP_NOT_FOUND")
    void start_notFound() {
        given(topupRepository.findByTossOrderIdAndMemberIdForUpdate("TOPUP-none", MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.start(MEMBER_ID, "TOPUP-none", "pk_1", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
    }

    @Test
    @DisplayName("승인 시작: READY가 아니면 409 TOPUP_ALREADY_PROCESSED이고 상태는 그대로다")
    void start_notReady() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1");

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("승인 시작: 상태가 READY가 아니면 금액이 달라도 실패로 바꾸지 않는다")
    void start_notReadyWithDifferentAmount() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1");

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_1", 10_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("승인 시작: 다른 충전 건에 이미 쓰인 결제키면 409 TOPUP_ALREADY_PROCESSED")
    void start_paymentKeyAlreadyUsed() {
        Topup topup = readyTopup(20_000);
        given(topupRepository.existsByPaymentKey("pk_used")).willReturn(true);

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getTossOrderId(), "pk_used", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.READY);
    }

    @Test
    @DisplayName("승인 반영: 지갑 잔액을 늘리고 원장 CHARGE를 남긴 뒤 충전 건을 DONE으로 바꾼다")
    void complete_success() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        wallet.deposit(5_000);
        Topup topup = processingTopup(wallet, 20_000);

        TopupConfirmDto result = processor.complete(TOPUP_ID, tossDone(topup, 20_000, "카드"));

        ArgumentCaptor<WalletTransaction> saved = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(WalletTransactionType.CHARGE);
        assertThat(saved.getValue().getAmount()).isEqualTo(20_000);
        assertThat(saved.getValue().getReferenceId()).isEqualTo(TOPUP_ID);
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("TOPUP-7-CHARGE");
        assertThat(wallet.getBalance()).isEqualTo(25_000);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getRemainingAmount()).isEqualTo(20_000);
        assertThat(topup.getApprovedAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 10, 0));
        assertThat(result.status()).isEqualTo(TopupStatus.DONE);
        assertThat(result.balance()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("승인 반영: 허용하지 않은 결제 수단(가상계좌 등)이어도 승인된 금액이므로 반영한다")
    void complete_notAllowedMethod() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        Topup topup = processingTopup(wallet, 10_000);

        processor.complete(TOPUP_ID, tossDone(topup, 10_000, "가상계좌"));

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("승인 반영: 이미 반영된(PROCESSING이 아닌) 충전 건은 다시 반영하지 않고 현재 상태를 돌려준다")
    void complete_alreadyApplied() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        wallet.deposit(20_000);
        Topup topup = processingTopup(wallet, 20_000);
        topup.approve(LocalDateTime.of(2026, 10, 13, 10, 0));

        TopupConfirmDto result = processor.complete(TOPUP_ID, tossDone(topup, 20_000, "카드"));

        verify(walletTransactionRepository, never()).save(any());
        assertThat(wallet.getBalance()).isEqualTo(20_000);
        assertThat(result.status()).isEqualTo(TopupStatus.DONE);
        assertThat(result.balance()).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("승인 반영: 토스 결제가 DONE이 아니면 반영할 수 없다")
    void complete_tossNotDone() {
        Topup topup = processingTopup(Wallet.create(MEMBER_ID), 20_000);
        TossPaymentResponse aborted = new TossPaymentResponse("pk_1", topup.getTossOrderId(), "ABORTED", 20_000, "카드", null);

        assertThatThrownBy(() -> processor.complete(TOPUP_ID, aborted))
                .isInstanceOf(IllegalArgumentException.class);
        verify(walletTransactionRepository, never()).save(any());
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("실패 기록: PROCESSING 충전 건을 실패 상태와 사유로 기록한다")
    void fail_processing() {
        Topup topup = processingTopup(Wallet.create(MEMBER_ID), 20_000);

        processor.fail(TOPUP_ID, TopupStatus.FAILED_PG_DECLINED, "한도초과 혹은 잔액부족으로 결제에 실패했습니다.");

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_PG_DECLINED);
        assertThat(topup.getFailReason()).isEqualTo("한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
    }

    @Test
    @DisplayName("실패 기록: 이미 반영된(PROCESSING이 아닌) 충전 건은 바꾸지 않는다")
    void fail_alreadyApplied() {
        Topup topup = processingTopup(Wallet.create(MEMBER_ID), 20_000);
        topup.approve(LocalDateTime.of(2026, 10, 13, 10, 0));

        processor.fail(TOPUP_ID, TopupStatus.FAILED_PG_DECLINED, "거절");

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
    }

    // 승인 반영 대상: ID가 있는 PROCESSING 충전 건과 그 지갑을 잠금 조회로 돌려줌
    private Topup processingTopup(Wallet wallet, long amount) {
        Topup topup = Topup.create(MEMBER_ID, wallet, amount);
        ReflectionTestUtils.setField(topup, "id", TOPUP_ID);
        topup.markProcessing("pk_1");
        given(topupRepository.findByIdForUpdate(TOPUP_ID)).willReturn(Optional.of(topup));
        given(walletRepository.findByMemberIdForUpdate(MEMBER_ID)).willReturn(Optional.of(wallet));
        return topup;
    }

    private TossPaymentResponse tossDone(Topup topup, long amount, String method) {
        return new TossPaymentResponse("pk_1", topup.getTossOrderId(), "DONE", amount, method, APPROVED_AT);
    }

    // 잠금 조회로 돌려줄 READY 충전 건
    private Topup readyTopup(long amount) {
        Topup topup = Topup.create(MEMBER_ID, Wallet.create(MEMBER_ID), amount);
        given(topupRepository.findByTossOrderIdAndMemberIdForUpdate(topup.getTossOrderId(), MEMBER_ID))
                .willReturn(Optional.of(topup));
        return topup;
    }
}
