package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.TopupUsageRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;

/**
 * 결제 취소(cancelPayment)를 실제 DB로 확인한다.
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest
class PaymentCancelPaymentIntegrationTest {

    private static final int THREADS = 5;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    @Autowired
    private TopupRepository topupRepository;

    @Autowired
    private TopupUsageRepository topupUsageRepository;

    // 다른 테스트·시드와 겹치지 않는 회원·주문 ID
    private final long buyerMemberId = randomId();
    private final long orderId = randomId();

    private Wallet wallet;
    private final List<Long> paymentIds = new ArrayList<>();
    private final List<Topup> topups = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Wallet created = Wallet.create(buyerMemberId);
        created.deposit(30_000);
        wallet = walletRepository.save(created);
    }

    @AfterEach
    void cleanUp() {
        for (Long paymentId : paymentIds) {
            topupUsageRepository.deleteAll(topupUsageRepository.findAllByPaymentId(paymentId));
            walletHoldingRepository.findAll().stream()
                    .filter(h -> h.getPayment().getId().equals(paymentId))
                    .forEach(walletHoldingRepository::delete);
            walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId)
                    .ifPresent(walletTransactionRepository::delete);
            walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId + "-CANCEL")
                    .ifPresent(walletTransactionRepository::delete);
            paymentRepository.deleteById(paymentId);
        }
        topupRepository.deleteAll(topups);
        walletRepository.delete(wallet);
    }

    @Test
    @DisplayName("결제를 취소하면 결제 CANCELED·홀딩 RELEASED·원장 PAYMENT_CANCEL이 남고, 돈은 지갑으로 돌아온다")
    void cancel() {
        String key = newKey();
        PayResultDto paid = pay(25_000, key);
        assertThat(balance()).isEqualTo(5_000);

        paymentFacade.cancelPayment(key);

        assertThat(balance()).isEqualTo(30_000);

        Payment payment = paymentRepository.findById(paid.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getCanceledAt()).isNotNull();

        WalletHolding holding = holdingOf(paid.paymentId());
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.RELEASED);
        assertThat(holding.getReleasedAt()).isNotNull();

        WalletTransaction ledger = walletTransactionRepository
                .findByIdempotencyKey("PAYMENT-" + paid.paymentId() + "-CANCEL").orElseThrow();
        assertThat(ledger.getType()).isEqualTo(WalletTransactionType.PAYMENT_CANCEL);
        assertThat(ledger.getAmount()).isEqualTo(25_000);
        assertThat(ledger.getBalanceAfter()).isEqualTo(30_000);
        assertThat(ledger.getReferenceId()).isEqualTo(paid.paymentId());
    }

    @Test
    @DisplayName("결제에 쓴 충전 건의 남은 금액을 되돌려, 되돌린 금액만큼 다시 취소할 수 있다")
    void cancel_restoresTopups() {
        Topup topup = doneTopup(20_000);
        String key = newKey();
        pay(25_000, key);
        assertThat(topupRepository.findById(topup.getId()).orElseThrow().getRemainingAmount()).isZero();

        paymentFacade.cancelPayment(key);

        Topup restored = topupRepository.findById(topup.getId()).orElseThrow();
        assertThat(restored.getRemainingAmount()).isEqualTo(20_000);
        assertThat(restored.cancelableAmount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("같은 키로 여러 번 취소해도 돈은 한 번만 돌아온다")
    void cancel_twice() {
        String key = newKey();
        pay(25_000, key);

        paymentFacade.cancelPayment(key);
        paymentFacade.cancelPayment(key);

        assertThat(balance()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("같은 키로 동시에 취소해도 예외 없이 돈은 한 번만 돌아온다 (지갑 잠금 뒤에 결제 상태를 다시 읽음)")
    void cancel_concurrent() throws Exception {
        String key = newKey();
        pay(25_000, key);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = IntStream.range(0, THREADS).<Callable<Void>>mapToObj(i -> () -> {
            start.await();
            paymentFacade.cancelPayment(key);
            return null;
        }).toList();
        List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertThat(balance()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("이 키로 된 결제가 없으면(결제 요청이 도착하지 못함) 아무것도 하지 않는다")
    void cancel_unknownKey() {
        paymentFacade.cancelPayment(newKey());

        assertThat(balance()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("실패한 결제는 돈이 움직이지 않았으므로 취소해도 아무것도 바뀌지 않는다")
    void cancel_failedPayment() {
        String key = newKey();
        PayResultDto failed = pay(40_000, key);
        assertThat(failed.status()).isEqualTo(PayResultStatus.FAILED_INSUFFICIENT_BALANCE);

        paymentFacade.cancelPayment(key);

        assertThat(balance()).isEqualTo(30_000);
        assertThat(paymentRepository.findById(failed.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED_INSUFFICIENT_BALANCE);
    }

    @Test
    @DisplayName("정산 지급이 시작된 결제는 취소할 수 없다 (409 PAYMENT_NOT_CANCELABLE)")
    void cancel_settlementStarted() {
        String key = newKey();
        PayResultDto paid = pay(25_000, key);
        transactionTemplate.executeWithoutResult(status -> {
            WalletHolding holding = walletHoldingRepository.findById(holdingOf(paid.paymentId()).getId()).orElseThrow();
            holding.settle(10_000, LocalDateTime.now());
        });

        assertThatThrownBy(() -> paymentFacade.cancelPayment(key))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PAYMENT_NOT_CANCELABLE");
        assertThat(balance()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("취소한 결제의 주문은 새 키로 다시 결제할 수 있다 (해제된 홀딩은 주문당 홀딩 1개 제약에서 빠짐)")
    void payAgain_afterCancel() {
        String key = newKey();
        pay(25_000, key);
        paymentFacade.cancelPayment(key);

        PayResultDto again = pay(25_000, newKey());

        assertThat(again.status()).isEqualTo(PayResultStatus.PAID);
        assertThat(balance()).isEqualTo(5_000);
        assertThat(holdingOf(again.paymentId()).getStatus()).isEqualTo(WalletHoldingStatus.HELD);
    }

    @Test
    @DisplayName("취소한 결제의 키로 다시 결제를 부르면 409 PAYMENT_CANCELED이고 돈은 그대로다")
    void payWithCanceledKey() {
        String key = newKey();
        pay(25_000, key);
        paymentFacade.cancelPayment(key);

        assertThatThrownBy(() -> pay(25_000, key))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PAYMENT_CANCELED");
        assertThat(balance()).isEqualTo(30_000);
    }

    private PayResultDto pay(long amount, String idempotencyKey) {
        PayResultDto result = paymentFacade.pay(new PayCommand(orderId, "ORD-TEST-" + orderId, buyerMemberId, amount,
                LocalDateTime.now().plusMinutes(15), idempotencyKey));
        if (!paymentIds.contains(result.paymentId())) paymentIds.add(result.paymentId());
        return result;
    }

    private Topup doneTopup(long amount) {
        Topup topup = Topup.create(buyerMemberId, wallet, amount, PgProvider.TOSS);
        topup.markProcessing("TEST-PK-" + UUID.randomUUID());
        topup.approve(LocalDateTime.now().minusDays(1));
        Topup saved = topupRepository.save(topup);
        topups.add(saved);
        return saved;
    }

    private WalletHolding holdingOf(Long paymentId) {
        return walletHoldingRepository.findAll().stream()
                .filter(h -> h.getPayment().getId().equals(paymentId))
                .findFirst().orElseThrow();
    }

    private long balance() {
        return walletRepository.findByMemberId(buyerMemberId).orElseThrow().getBalance();
    }

    private static String newKey() {
        return "TEST-" + UUID.randomUUID();
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }
}
