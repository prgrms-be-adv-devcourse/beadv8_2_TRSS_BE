package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backend.boundedContext.payment.app.dto.TopupCancelDto;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.TopupUsageRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.boundedContext.payment.out.toss.TossPaymentException;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;
import com.backend.boundedContext.payment.out.toss.TossTimeoutException;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;

/**
 * 충전 취소 흐름을 실제 DB로 확인한다 (토스 클라이언트만 mock, 토스 어댑터·PG 포트는 실제 빈).
 * 잔액 먼저 차감, 거절 시 되돌림, 결과 모름, 취소 중 결제·결제 취소, 다시 취소를 본다.
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class TopupCancelIntegrationTest {

    private static final long AMOUNT = 30_000;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private TopupUsageRepository topupUsageRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTopupCancelProcessor paymentTopupCancelProcessor;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    @MockitoBean   // 회원 서비스 대신 쓰는 목. checkActiveMember는 아무것도 하지 않으므로 활성 회원으로 통과한다
    private PaymentMemberPort memberPort;

    private final List<Long> paymentIds = new ArrayList<>();
    private Long memberId;
    private Wallet wallet;
    private Topup topup;

    @BeforeEach
    void setUp() {
        memberId = randomMemberId();
        Wallet created = Wallet.create(memberId);
        created.deposit(AMOUNT);   // 충전 반영(CHARGE)을 거친 것처럼 충전 금액만큼 잔액을 넣어 둠
        wallet = walletRepository.save(created);

        Topup done = Topup.create(memberId, wallet, AMOUNT, PgProvider.TOSS);
        done.markProcessing("TEST-PK-" + UUID.randomUUID());
        done.approve(LocalDateTime.now().minusDays(1));
        topup = topupRepository.save(done);
    }

    @AfterEach
    void cleanUp() {
        for (Long paymentId : paymentIds) {
            topupUsageRepository.deleteAll(topupUsageRepository.findAllByPaymentId(paymentId));
            walletHoldingRepository.findAll().stream()
                    .filter(h -> h.getPayment().getId().equals(paymentId))
                    .forEach(walletHoldingRepository::delete);
        }
        walletTransactionRepository.deleteAll(transactionsOfWallet());
        paymentIds.forEach(paymentRepository::deleteById);
        topupRepository.deleteById(topup.getId());
        walletRepository.deleteById(wallet.getId());
    }

    @Test
    @DisplayName("한 번도 안 쓴 충전 건: 전액 취소돼 CANCELED, 잔액 0, 원장 CHARGE_CANCEL 1건")
    void cancel_full() {
        given(tossPaymentsClient.cancel(anyString(), eq(AMOUNT), anyString(), anyString())).willReturn(tossCanceled(AMOUNT));

        TopupCancelDto result = paymentFacade.cancelTopup(memberId, topup.getId());

        assertThat(result.status()).isEqualTo(TopupStatus.CANCELED);
        assertThat(result.cancelAmount()).isEqualTo(AMOUNT);
        assertThat(result.balance()).isZero();
        verify(tossPaymentsClient).cancel(topup.getPaymentKey(), AMOUNT, "예치금 충전 취소", "TOPUP-" + topup.getId() + "-CANCEL-0");
        assertThat(savedTopup().getCanceledAmount()).isEqualTo(AMOUNT);
        assertThat(transactionsOfWallet()).extracting(WalletTransaction::getType).containsExactly(WalletTransactionType.CHARGE_CANCEL);
    }

    @Test
    @DisplayName("일부 쓴 충전 건: 남은 금액만 부분 취소되고 DONE으로 남는다")
    void cancel_partial() {
        pay(10_000);
        given(tossPaymentsClient.cancel(anyString(), eq(20_000L), anyString(), anyString())).willReturn(tossCanceled(20_000));

        TopupCancelDto result = paymentFacade.cancelTopup(memberId, topup.getId());

        assertThat(result.status()).isEqualTo(TopupStatus.DONE);
        assertThat(result.cancelAmount()).isEqualTo(20_000);
        assertThat(result.canceledAmount()).isEqualTo(20_000);
        assertThat(balance()).isZero();
        assertThat(savedTopup().cancelableAmount()).isZero();
    }

    @Test
    @DisplayName("PG 거절: 잔액·남은 금액이 원래대로 돌아오고 원장에 CHARGE_CANCEL·CHARGE_CANCEL_RESTORE가 남는다")
    void cancel_declined() {
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString()))
                .willThrow(new TossPaymentException(403, "EXCEED_MAX_REFUND_DUE", "환불 가능한 기간이 지났습니다.", null));

        assertThatThrownBy(() -> paymentFacade.cancelTopup(memberId, topup.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-TOPUP_CANCEL_DECLINED");

        Topup restored = savedTopup();
        assertThat(restored.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(restored.getRemainingAmount()).isEqualTo(AMOUNT);
        assertThat(restored.getCancelingAmount()).isZero();
        assertThat(balance()).isEqualTo(AMOUNT);
        assertThat(transactionsOfWallet()).extracting(WalletTransaction::getType)
                .containsExactlyInAnyOrder(WalletTransactionType.CHARGE_CANCEL, WalletTransactionType.CHARGE_CANCEL_RESTORE);
    }

    @Test
    @DisplayName("PG 결과 모름: CANCELING으로 남고 잔액은 빠진 채로 둔다")
    void cancel_unknown() {
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString()))
                .willThrow(new TossTimeoutException(new RuntimeException("Read timed out")));

        TopupCancelDto result = paymentFacade.cancelTopup(memberId, topup.getId());

        assertThat(result.status()).isEqualTo(TopupStatus.CANCELING);
        assertThat(savedTopup().getStatus()).isEqualTo(TopupStatus.CANCELING);
        assertThat(savedTopup().getCancelingAmount()).isEqualTo(AMOUNT);
        assertThat(balance()).isZero();
        assertThat(paymentFacade.hasPendingTopups(memberId)).isTrue();   // 탈퇴 전 확인: 취소 중인 충전 건은 결과 대기
    }

    @Test
    @DisplayName("취소할 남은 금액이 없으면 PG를 호출하지 않고 409 TOPUP_NOT_CANCELABLE")
    void cancel_usedUp() {
        pay(AMOUNT);

        assertThatThrownBy(() -> paymentFacade.cancelTopup(memberId, topup.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TOPUP_NOT_CANCELABLE");
        verify(tossPaymentsClient, times(0)).cancel(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("남의 충전 건이면 404 TOPUP_NOT_FOUND")
    void cancel_otherMember() {
        Wallet other = walletRepository.save(Wallet.create(randomMemberId()));
        try {
            assertThatThrownBy(() -> paymentFacade.cancelTopup(other.getMemberId(), topup.getId()))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("404-TOPUP_NOT_FOUND");
        } finally {
            walletRepository.delete(other);
        }
    }

    @Test
    @DisplayName("취소 중인 금액은 결제에 쓰이지 않는다: PG 응답을 기다리는 동안 결제는 잔액 부족으로 실패한다")
    void payDuringCanceling() throws Exception {
        CountDownLatch pgCalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString())).willAnswer(invocation -> {
            pgCalled.countDown();
            release.await(10, TimeUnit.SECONDS);
            return tossCanceled(AMOUNT);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<TopupCancelDto> cancel = executor.submit(() -> paymentFacade.cancelTopup(memberId, topup.getId()));
        assertThat(pgCalled.await(10, TimeUnit.SECONDS)).isTrue();

        PayResultDto paid = pay(AMOUNT);
        release.countDown();
        TopupCancelDto canceled = cancel.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(paid.status()).isEqualTo(PayResultStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(canceled.status()).isEqualTo(TopupStatus.CANCELED);
        assertThat(balance()).isZero();
    }

    @Test
    @DisplayName("같은 충전 건을 동시에 두 번 취소해도 PG 취소와 잔액 차감은 한 번만 일어난다")
    void cancel_concurrent() throws Exception {
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString())).willReturn(tossCanceled(AMOUNT));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> task = () -> {
            start.await();
            try {
                return paymentFacade.cancelTopup(memberId, topup.getId());
            } catch (DomainException e) {
                return e.getResultCode();
            }
        };
        List<Future<Object>> futures = List.of(executor.submit(task), executor.submit(task));
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        assertThat(results).filteredOn(TopupCancelDto.class::isInstance).hasSize(1);
        assertThat(results).filteredOn("409-TOPUP_NOT_CANCELABLE"::equals).hasSize(1);
        verify(tossPaymentsClient, times(1)).cancel(anyString(), anyLong(), anyString(), anyString());
        assertThat(balance()).isZero();
        assertThat(transactionsOfWallet()).hasSize(1);
    }

    @Test
    @DisplayName("일부 취소 뒤 결제가 취소되면 되살아난 남은 금액을 다른 멱등 키로 다시 취소해 CANCELED가 된다")
    void cancelAgain_afterPaymentCancel() {
        String payKey = newKey();
        pay(10_000, payKey);
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString())).willReturn(tossCanceled(20_000));
        paymentFacade.cancelTopup(memberId, topup.getId());

        paymentFacade.cancelPayment(payKey);
        assertThat(savedTopup().cancelableAmount()).isEqualTo(10_000);
        assertThat(balance()).isEqualTo(10_000);

        TopupCancelDto again = paymentFacade.cancelTopup(memberId, topup.getId());

        assertThat(again.status()).isEqualTo(TopupStatus.CANCELED);
        assertThat(again.canceledAmount()).isEqualTo(AMOUNT);
        assertThat(balance()).isZero();
        verify(tossPaymentsClient).cancel(topup.getPaymentKey(), 20_000, "예치금 충전 취소", "TOPUP-" + topup.getId() + "-CANCEL-0");
        verify(tossPaymentsClient).cancel(topup.getPaymentKey(), 10_000, "예치금 충전 취소", "TOPUP-" + topup.getId() + "-CANCEL-20000");
    }

    @Test
    @DisplayName("취소 중에 결제가 취소되고 PG가 취소를 거절하면, 되살아난 금액과 취소하려던 금액이 모두 남은 금액으로 돌아온다")
    void paymentCancelDuringCanceling_thenDeclined() {
        String payKey = newKey();
        pay(10_000, payKey);
        given(tossPaymentsClient.cancel(anyString(), anyLong(), anyString(), anyString()))
                .willThrow(new TossTimeoutException(new RuntimeException("Read timed out")));
        paymentFacade.cancelTopup(memberId, topup.getId());   // CANCELING, 취소 중 20,000
        paymentFacade.cancelPayment(payKey);                  // 남은 금액 +10,000, 잔액 +10,000

        paymentTopupCancelProcessor.fail(topup.getId());     // 대사가 PG 조회로 취소 미반영을 확인한 것처럼 확정 실패 반영

        Topup restored = savedTopup();
        assertThat(restored.getStatus()).isEqualTo(TopupStatus.DONE);
        assertThat(restored.getRemainingAmount()).isEqualTo(AMOUNT);
        assertThat(balance()).isEqualTo(AMOUNT);
    }

    private PayResultDto pay(long amount) {
        return pay(amount, newKey());
    }

    private PayResultDto pay(long amount, String idempotencyKey) {
        long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        PayResultDto result = paymentFacade.pay(new PayCommand(orderId, "ORD-TEST-" + orderId, memberId, amount,
                LocalDateTime.now().plusMinutes(15), idempotencyKey));
        if (result.paymentId() != null && !paymentIds.contains(result.paymentId())) paymentIds.add(result.paymentId());
        return result;
    }

    private TossPaymentResponse tossCanceled(long cancelAmount) {
        return new TossPaymentResponse(topup.getPaymentKey(), topup.getTossOrderId(), "PARTIAL_CANCELED", AMOUNT, "카드", null,
                List.of(new TossPaymentResponse.Cancel(cancelAmount, "DONE")));
    }

    private Topup savedTopup() {
        return topupRepository.findById(topup.getId()).orElseThrow();
    }

    private long balance() {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private List<WalletTransaction> transactionsOfWallet() {
        return walletTransactionRepository.findAll().stream()
                .filter(transaction -> transaction.getWallet().getId().equals(wallet.getId()))
                .toList();
    }

    private static String newKey() {
        return "TEST-" + UUID.randomUUID();
    }

    // 다른 테스트·시드와 겹치지 않는 회원 ID (지갑의 member_id 유니크)
    private static Long randomMemberId() {
        return ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
    }
}
