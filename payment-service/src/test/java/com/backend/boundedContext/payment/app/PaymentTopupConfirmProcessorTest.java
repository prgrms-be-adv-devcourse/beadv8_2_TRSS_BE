package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.payment.support.TestWalletCharger;
import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;
import com.backend.global.exception.DomainException;
import com.backend.boundedContext.payment.support.TestPgCircuitBreakers;

class PaymentTopupConfirmProcessorTest {

    private static final Long MEMBER_ID = 3L;

    private static final Long TOPUP_ID = 7L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 13, 10, 0);

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletTransactionRepository walletTransactionRepository = mock(WalletTransactionRepository.class);
    private final TopupGateways topupGateways = new TopupGateways(List.of(gateway(PgProvider.TOSS)), TestPgCircuitBreakers.create());
    private final PaymentTopupConfirmProcessor processor =
            new PaymentTopupConfirmProcessor(topupRepository, walletRepository, walletTransactionRepository, topupGateways);

    @Test
    @DisplayName("승인 시작: READY 충전 건을 잠금 조회해 PROCESSING으로 바꾸고 결제키·승인 요청 시각을 저장한다")
    void start_success() {
        Topup topup = readyTopup(20_000);

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 20_000);

        assertThat(result.isStarted()).isTrue();
        assertThat(result.pgProvider()).isEqualTo(PgProvider.TOSS);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
        assertThat(topup.getPaymentKey()).isEqualTo("pk_1");
        assertThat(topup.getConfirmRequestedAt()).isNotNull();
        assertThat(result.payment()).isEqualTo(PgPaymentRef.from(topup));
    }

    @Test
    @DisplayName("승인 시작: 금액이 다르면 FAILED_AMOUNT_MISMATCH로 기록하고 예외 대신 결과로 돌려준다")
    void start_amountMismatch() {
        Topup topup = readyTopup(20_000);

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 10_000);

        assertThat(result.outcome()).isEqualTo(TopupConfirmStartResult.Outcome.AMOUNT_MISMATCH);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_AMOUNT_MISMATCH);
        assertThat(topup.getFailReason()).contains("20000", "10000");
        assertThat(topup.getPaymentKey()).isNull();
    }

    @Test
    @DisplayName("승인 시작: 충전 건이 없거나 남의 충전 건이면 404 TOPUP_NOT_FOUND")
    void start_notFound() {
        given(topupRepository.findByOrderIdAndMemberIdForUpdate("TOPUP-none", MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.start(MEMBER_ID, "TOPUP-none", "pk_1", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
    }

    @Test
    @DisplayName("승인 재요청: PROCESSING 충전 건에 같은 결제키·금액이 오면 결과 확인 중으로 돌려주고 상태는 그대로다")
    void start_replayProcessing() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1", LocalDateTime.now());

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 20_000);

        assertThat(result.isReplayed()).isTrue();
        assertThat(result.replay().status()).isEqualTo(TopupStatus.PROCESSING);
        assertThat(result.replay().orderId()).isEqualTo(topup.getOrderId());
        assertThat(result.replay().amount()).isEqualTo(20_000);
        assertThat(result.replay().balance()).isNull();
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
        verify(topupRepository, never()).existsByPaymentKey(any());
    }

    @Test
    @DisplayName("승인 재요청: DONE 충전 건에 같은 결제키·금액이 오면 승인 완료와 지금 지갑 잔액을 돌려준다")
    void start_replayDone() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        TestWalletCharger.charge(wallet, 5_000);
        Topup topup = readyTopup(wallet, 20_000);
        topup.markProcessing("pk_1", LocalDateTime.now());
        topup.approve(APPROVED_AT);

        TopupConfirmStartResult result = processor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 20_000);

        assertThat(result.isReplayed()).isTrue();
        assertThat(result.replay().status()).isEqualTo(TopupStatus.DONE);
        assertThat(result.replay().balance()).isEqualTo(5_000);
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("승인 재요청: 결제키가 다르면 같은 요청이 아니므로 409 TOPUP_ALREADY_PROCESSED")
    void start_replayDifferentPaymentKey() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1", LocalDateTime.now());

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getOrderId(), "pk_other", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getPaymentKey()).isEqualTo("pk_1");
    }

    @Test
    @DisplayName("승인 재요청: 실패한 충전 건은 처음 오류 응답을 다시 만들 수 없어 409 TOPUP_ALREADY_PROCESSED")
    void start_replayFailed() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1", LocalDateTime.now());
        topup.fail(TopupStatus.FAILED_PG_DECLINED, "카드 거절");

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_PG_DECLINED);
    }

    @Test
    @DisplayName("승인 재요청: 취소가 시작된 충전 건은 승인 완료로 답할 수 없어 409 TOPUP_ALREADY_PROCESSED")
    void start_replayCanceling() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1", LocalDateTime.now());
        topup.approve(APPROVED_AT);
        topup.startCancel();

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CANCELING);
    }

    @Test
    @DisplayName("승인 시작: 상태가 READY가 아니면 금액이 달라도 실패로 바꾸지 않는다")
    void start_notReadyWithDifferentAmount() {
        Topup topup = readyTopup(20_000);
        topup.markProcessing("pk_1", LocalDateTime.now());

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 10_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PROCESSING);
    }

    @Test
    @DisplayName("승인 시작: 다른 충전 건에 이미 쓰인 결제키면 409 TOPUP_ALREADY_PROCESSED")
    void start_paymentKeyAlreadyUsed() {
        Topup topup = readyTopup(20_000);
        given(topupRepository.existsByPaymentKey("pk_used")).willReturn(true);

        assertThatThrownBy(() -> processor.start(MEMBER_ID, topup.getOrderId(), "pk_used", 20_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_ALREADY_PROCESSED");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.READY);
    }

    @Test
    @DisplayName("승인 시작: 충전 건의 PG가 연동되지 않았으면 선점하지 않고 IllegalStateException")
    void start_pgNotSupported() {
        PaymentTopupConfirmProcessor noPgProcessor =
                new PaymentTopupConfirmProcessor(topupRepository, walletRepository, walletTransactionRepository, new TopupGateways(List.of(), TestPgCircuitBreakers.create()));
        Topup topup = readyTopup(20_000);

        assertThatThrownBy(() -> noPgProcessor.start(MEMBER_ID, topup.getOrderId(), "pk_1", 20_000))
                .isInstanceOf(IllegalStateException.class);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.READY);
        assertThat(topup.getPaymentKey()).isNull();
    }

    @Test
    @DisplayName("승인 반영: 지갑 잔액을 늘리고 원장 CHARGE를 남긴 뒤 충전 건을 DONE으로 바꾼다")
    void complete_success() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        TestWalletCharger.charge(wallet, 5_000);
        Topup topup = processingTopup(wallet, 20_000);

        TopupConfirmDto result = processor.complete(TOPUP_ID, approved(topup, 20_000, PgPaymentMethod.CARD));

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
    @DisplayName("승인 반영: 허용하지 않은 결제 수단(카드·간편결제·계좌이체 외)이어도 승인된 금액이므로 반영한다")
    void complete_notAllowedMethod() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        Topup topup = processingTopup(wallet, 10_000);

        processor.complete(TOPUP_ID, approved(topup, 10_000, PgPaymentMethod.OTHER));

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("승인 반영: 이미 반영된(PROCESSING이 아닌) 충전 건은 다시 반영하지 않고 현재 상태를 돌려준다")
    void complete_alreadyApplied() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        TestWalletCharger.charge(wallet, 20_000);
        Topup topup = processingTopup(wallet, 20_000);
        topup.approve(LocalDateTime.of(2026, 10, 13, 10, 0));

        TopupConfirmDto result = processor.complete(TOPUP_ID, approved(topup, 20_000, PgPaymentMethod.CARD));

        verify(walletTransactionRepository, never()).save(any());
        assertThat(wallet.getBalance()).isEqualTo(20_000);
        assertThat(result.status()).isEqualTo(TopupStatus.DONE);
        assertThat(result.balance()).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("승인 반영: PG 결제가 승인(APPROVED)이 아니면 반영할 수 없다")
    void complete_notApproved() {
        Topup topup = processingTopup(Wallet.create(MEMBER_ID), 20_000);
        PgPaymentResult aborted = new PgPaymentResult("pk_1", topup.getOrderId(), PgPaymentStatus.NOT_APPROVED, "ABORTED", 20_000, 0, PgPaymentMethod.CARD, null);

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

    @Test
    @DisplayName("허용하지 않은 결제 수단 취소 시작: PROCESSING → METHOD_CANCELING, 지갑·원장은 건드리지 않고 PG 취소에 필요한 값을 돌려준다")
    void startMethodCancel() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        Topup topup = processingTopup(wallet, 20_000);

        Optional<TopupMethodCancelStart> start = processor.startMethodCancel(TOPUP_ID);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.METHOD_CANCELING);
        assertThat(start).hasValueSatisfying(s -> {
            assertThat(s.payment()).isEqualTo(PgPaymentRef.from(topup));
            assertThat(s.amount()).isEqualTo(20_000);
            assertThat(s.attempt()).isEqualTo(1);
            assertThat(s.idempotencyKey()).isEqualTo("TOPUP-" + TOPUP_ID + "-METHOD-CANCEL-1");
        });
        assertThat(wallet.getBalance()).isZero();
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("허용하지 않은 결제 수단 취소 시작: PROCESSING이 아니면(다른 쪽이 먼저 처리) 아무것도 하지 않는다")
    void startMethodCancel_notProcessing() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        Topup topup = processingTopup(wallet, 20_000);
        topup.startMethodCancel();

        assertThat(processor.startMethodCancel(TOPUP_ID)).isEmpty();
        assertThat(topup.getCancelAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("허용하지 않은 결제 수단 취소 확정: 같은 회차면 FAILED_METHOD_NOT_ALLOWED, 회차가 다르면 그대로")
    void completeMethodCancel() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        Topup topup = processingTopup(wallet, 20_000);
        topup.startMethodCancel();
        topup.retryMethodCancel();

        processor.completeMethodCancel(TOPUP_ID, 1, "사유");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.METHOD_CANCELING);

        processor.completeMethodCancel(TOPUP_ID, 2, "사유");
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED_METHOD_NOT_ALLOWED);
        assertThat(topup.getCanceledAmount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("허용하지 않은 결제 수단 취소 재시도: 같은 회차면 회차를 늘려 새 멱등 키를 돌려주고, 다르면 빈 값")
    void retryMethodCancel() {
        Wallet wallet = Wallet.create(MEMBER_ID);
        Topup topup = processingTopup(wallet, 20_000);
        topup.startMethodCancel();

        assertThat(processor.retryMethodCancel(TOPUP_ID, 1)).hasValueSatisfying(s -> {
            assertThat(s.attempt()).isEqualTo(2);
            assertThat(s.idempotencyKey()).isEqualTo("TOPUP-" + TOPUP_ID + "-METHOD-CANCEL-2");
        });
        assertThat(processor.retryMethodCancel(TOPUP_ID, 1)).isEmpty();
        assertThat(topup.getCancelAttempts()).isEqualTo(2);
    }

    // 승인 반영 대상: ID가 있는 PROCESSING 충전 건과 그 지갑을 잠금 조회로 돌려줌
    private Topup processingTopup(Wallet wallet, long amount) {
        Topup topup = Topup.create(MEMBER_ID, wallet, amount, PgProvider.TOSS, LocalDateTime.now());
        ReflectionTestUtils.setField(topup, "id", TOPUP_ID);
        topup.markProcessing("pk_1", LocalDateTime.now());
        given(topupRepository.findByIdForUpdate(TOPUP_ID)).willReturn(Optional.of(topup));
        given(walletRepository.findByMemberIdForUpdate(MEMBER_ID)).willReturn(Optional.of(wallet));
        return topup;
    }

    private PgPaymentResult approved(Topup topup, long amount, PgPaymentMethod method) {
        return new PgPaymentResult("pk_1", topup.getOrderId(), PgPaymentStatus.APPROVED, "DONE", amount, 0, method, APPROVED_AT);
    }

    private static TopupGatewayPort gateway(PgProvider provider) {
        TopupGatewayPort port = mock(TopupGatewayPort.class);
        given(port.provider()).willReturn(provider);
        return port;
    }

    // 잠금 조회로 돌려줄 READY 충전 건
    private Topup readyTopup(long amount) {
        return readyTopup(Wallet.create(MEMBER_ID), amount);
    }

    private Topup readyTopup(Wallet wallet, long amount) {
        Topup topup = Topup.create(MEMBER_ID, wallet, amount, PgProvider.TOSS, LocalDateTime.now());
        given(topupRepository.findByOrderIdAndMemberIdForUpdate(topup.getOrderId(), MEMBER_ID))
                .willReturn(Optional.of(topup));
        return topup;
    }
}
