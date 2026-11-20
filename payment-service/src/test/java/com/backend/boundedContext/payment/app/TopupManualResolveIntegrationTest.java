package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backend.boundedContext.payment.app.dto.AdminTopupManualResolveDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupManualCheckReason;
import com.backend.boundedContext.payment.domain.TopupManualResolveAction;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;
import com.backend.boundedContext.payment.support.TestPgCircuitBreakers;
import com.backend.global.exception.DomainException;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * 관리자 충전 수동 확인 정리를 실제 DB로 확인한다 (토스 클라이언트만 mock).
 * CANCELING 확정·되돌림의 잔액·원장, METHOD_CANCELING 확정·다시 요청, 표시 안 된 건·상태에 맞지 않는 정리 거절을 본다.
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupManualResolveIntegrationTest {

    private static final long AMOUNT = 20_000;
    private static final Long ADMIN_ID = 1L;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private PaymentTopupConfirmProcessor paymentTopupConfirmProcessor;

    @Autowired
    private PaymentTopupCancelProcessor paymentTopupCancelProcessor;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    @MockitoBean   // 회원 서비스 대신 쓰는 목. checkActiveMember는 아무것도 하지 않으므로 활성 회원으로 통과한다
    private PaymentMemberPort memberPort;

    private Long memberId;

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        TestPgCircuitBreakers.resetAll(circuitBreakerRegistry);
        memberId = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
        wallet = walletRepository.save(Wallet.create(memberId));
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAll(transactionsOfWallet());
        topupRepository.deleteAll(topupRepository.findAll().stream()
                .filter(topup -> topup.getMemberId().equals(memberId)).toList());
        walletRepository.deleteById(wallet.getId());
    }

    @Test
    @DisplayName("CANCELING 취소 확정: 전부 취소면 CANCELED, 잔액은 취소 시작 때 뺀 그대로이고 표시가 지워진다")
    void confirmCancel() {
        Long topupId = markedCanceling();

        AdminTopupManualResolveDto result = paymentFacade.resolveTopupManualCheck(ADMIN_ID, topupId, TopupManualResolveAction.CONFIRM_CANCEL);

        Topup topup = topupRepository.findById(topupId).orElseThrow();
        assertThat(result.status()).isEqualTo(TopupStatus.CANCELED);
        assertThat(topup.getCanceledAmount()).isEqualTo(AMOUNT);
        assertThat(topup.needsManualCheck()).isFalse();
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isZero();
    }

    @Test
    @DisplayName("CANCELING 되돌림: DONE으로 돌아가 잔액·남은 금액이 복원되고 원장 CHARGE_CANCEL_RESTORE가 남는다")
    void restore() {
        Long topupId = markedCanceling();

        AdminTopupManualResolveDto result = paymentFacade.resolveTopupManualCheck(ADMIN_ID, topupId, TopupManualResolveAction.RESTORE);

        Topup topup = topupRepository.findById(topupId).orElseThrow();
        assertThat(result.status()).isEqualTo(TopupStatus.DONE);
        assertThat(topup.getRemainingAmount()).isEqualTo(AMOUNT);
        assertThat(topup.needsManualCheck()).isFalse();
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance()).isEqualTo(AMOUNT);
        assertThat(transactionsOfWallet()).extracting(WalletTransaction::getType)
                .containsExactlyInAnyOrder(WalletTransactionType.CHARGE, WalletTransactionType.CHARGE_CANCEL, WalletTransactionType.CHARGE_CANCEL_RESTORE);
    }

    @Test
    @DisplayName("METHOD_CANCELING 확정: FAILED_METHOD_NOT_ALLOWED가 되고 예치금은 그대로다")
    void confirmMethodCancel() {
        Long topupId = markedMethodCanceling();

        AdminTopupManualResolveDto result = paymentFacade.resolveTopupManualCheck(ADMIN_ID, topupId, TopupManualResolveAction.CONFIRM_METHOD_CANCEL);

        Topup topup = topupRepository.findById(topupId).orElseThrow();
        assertThat(result.status()).isEqualTo(TopupStatus.FAILED_METHOD_NOT_ALLOWED);
        assertThat(topup.getFailReason()).isEqualTo(PaymentResolveTopupManualCheckUseCase.CONFIRM_METHOD_CANCEL_REASON);
        assertThat(topup.needsManualCheck()).isFalse();
        assertThat(transactionsOfWallet()).isEmpty();
    }

    @Test
    @DisplayName("METHOD_CANCELING 다시 요청: 다음 회차 키로 PG 전액 취소를 보내고 성공하면 확정한다")
    void retryMethodCancel() {
        Long topupId = markedMethodCanceling();
        Topup before = topupRepository.findById(topupId).orElseThrow();
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString()))
                .willReturn(new TossPaymentResponse(before.getPaymentKey(), before.getOrderId(), "CANCELED", AMOUNT, "가상계좌", null, null));

        AdminTopupManualResolveDto result = paymentFacade.resolveTopupManualCheck(ADMIN_ID, topupId, TopupManualResolveAction.RETRY_METHOD_CANCEL);

        assertThat(result.status()).isEqualTo(TopupStatus.FAILED_METHOD_NOT_ALLOWED);
        assertThat(topupRepository.findById(topupId).orElseThrow().needsManualCheck()).isFalse();
        verify(tossPaymentsClient).cancel(before.getPaymentKey(), AMOUNT, "충전에 허용하지 않은 결제 수단", "TOPUP-" + topupId + "-METHOD-CANCEL-2");
    }

    @Test
    @DisplayName("METHOD_CANCELING 다시 요청이 실패하면 METHOD_CANCELING으로 남고 표시가 지워져 대사가 다시 확인한다")
    void retryMethodCancel_failed() {
        Long topupId = markedMethodCanceling();
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString())).willThrow(new RuntimeException("Read timed out"));

        AdminTopupManualResolveDto result = paymentFacade.resolveTopupManualCheck(ADMIN_ID, topupId, TopupManualResolveAction.RETRY_METHOD_CANCEL);

        Topup topup = topupRepository.findById(topupId).orElseThrow();
        assertThat(result.status()).isEqualTo(TopupStatus.METHOD_CANCELING);
        assertThat(topup.getStatus()).isEqualTo(TopupStatus.METHOD_CANCELING);
        assertThat(topup.needsManualCheck()).isFalse();
        assertThat(topup.getCancelAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("수동 확인으로 표시되지 않은 건이나 상태에 맞지 않는 정리는 409이고 아무것도 바뀌지 않는다")
    void rejected() {
        Long notMarked = canceling();
        assertThatThrownBy(() -> paymentFacade.resolveTopupManualCheck(ADMIN_ID, notMarked, TopupManualResolveAction.RESTORE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_NOT_MANUAL_CHECK");

        Long marked = markedMethodCanceling();
        assertThatThrownBy(() -> paymentFacade.resolveTopupManualCheck(ADMIN_ID, marked, TopupManualResolveAction.RESTORE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_MANUAL_RESOLVE_NOT_ALLOWED");

        assertThat(topupRepository.findById(notMarked).orElseThrow().getStatus()).isEqualTo(TopupStatus.CANCELING);
        Topup unchanged = topupRepository.findById(marked).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(TopupStatus.METHOD_CANCELING);
        assertThat(unchanged.needsManualCheck()).isTrue();
    }

    // 충전 승인(DONE·잔액 반영) 후 전액 취소를 시작해 CANCELING 건을 만든다 (잔액은 취소 시작 때 빠져 0)
    private Long canceling() {
        TopupPrepareDto prepared = paymentFacade.prepareTopup(memberId, AMOUNT);
        String paymentKey = "pk_resolve_" + prepared.topupId();
        paymentTopupConfirmProcessor.start(memberId, prepared.orderId(), paymentKey, AMOUNT);
        paymentTopupConfirmProcessor.complete(prepared.topupId(), new PgPaymentResult(paymentKey, prepared.orderId(), PgPaymentStatus.APPROVED, "DONE",
                AMOUNT, 0, PgPaymentMethod.CARD, LocalDateTime.now()));
        paymentTopupCancelProcessor.start(memberId, prepared.topupId());
        return prepared.topupId();
    }

    private Long markedCanceling() {
        Long topupId = canceling();
        paymentTopupCancelProcessor.markManualCheck(topupId, 1, TopupManualCheckReason.CANCEL_AMOUNT_MISMATCH);
        return topupId;
    }

    // 승인 시작 후 허용하지 않은 결제 수단 취소를 시작하고(회차 1) 수동 확인으로 표시한다
    private Long markedMethodCanceling() {
        TopupPrepareDto prepared = paymentFacade.prepareTopup(memberId, AMOUNT);
        paymentTopupConfirmProcessor.start(memberId, prepared.orderId(), "pk_resolve_" + prepared.topupId(), AMOUNT);
        paymentTopupConfirmProcessor.startMethodCancel(prepared.topupId());
        paymentTopupConfirmProcessor.markMethodCancelManualCheck(prepared.topupId(), 1, TopupManualCheckReason.METHOD_CANCEL_PARTIAL);
        return prepared.topupId();
    }

    private List<WalletTransaction> transactionsOfWallet() {
        return walletTransactionRepository.findAll().stream()
                .filter(transaction -> transaction.getWallet().getId().equals(wallet.getId()))
                .toList();
    }
}
