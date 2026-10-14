package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.out.PaymentApi;

/**
 * 같은 지갑·같은 주문으로 결제가 동시에 들어와도 잔액과 결제 건이 틀어지지 않는지 확인한다.
 * 다른 컨텍스트가 쓰는 경로 그대로 PaymentApi로 호출한다 (요청마다 각자 트랜잭션).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class PaymentPayConcurrencyTest {

    private static final int THREADS = 10;

    @Autowired
    private PaymentApi paymentApi;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    // 다른 테스트·시드와 겹치지 않는 회원 ID
    private final long buyerMemberId = randomId();

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        Wallet created = Wallet.create(buyerMemberId);
        created.deposit(30_000);
        wallet = walletRepository.save(created);
    }

    @AfterEach
    void cleanUp() {
        List<Payment> payments = paymentRepository.findAll().stream()
                .filter(p -> p.getMemberId().equals(buyerMemberId))
                .toList();
        List<Long> paymentIds = payments.stream().map(Payment::getId).toList();
        walletHoldingRepository.findAll().stream()
                .filter(h -> paymentIds.contains(h.getPayment().getId()))
                .forEach(walletHoldingRepository::delete);
        for (Long paymentId : paymentIds) {
            walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId)
                    .ifPresent(walletTransactionRepository::delete);
        }
        paymentRepository.deleteAll(payments);
        walletRepository.delete(wallet);
    }

    @Test
    @DisplayName("잔액 30,000 지갑에 10,000원 결제 10건이 동시에 오면 3건만 성공하고 잔액은 0이다 (음수 없음)")
    void pay_sameWallet_concurrent() throws InterruptedException {
        ConcurrentLinkedQueue<PayResultDto> results = new ConcurrentLinkedQueue<>();

        runConcurrently(() -> results.add(pay(randomId(), 10_000)));

        assertThat(results).hasSize(THREADS);
        assertThat(results).filteredOn(PayResultDto::isPaid).hasSize(3);
        assertThat(results).filteredOn(r -> r.status() == PayResultStatus.FAILED_INSUFFICIENT_BALANCE).hasSize(7);
        assertThat(walletRepository.findByMemberId(buyerMemberId).orElseThrow().getBalance()).isZero();
        assertThat(walletHoldingRepository.findAll().stream()
                .filter(h -> h.getWallet().getId().equals(wallet.getId()))
                .count()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 주문을 서로 다른 멱등 키로 동시에 결제하면 PAID는 1건이고 나머지는 409 PAYMENT_ALREADY_PAID다")
    void pay_sameOrder_concurrent() throws InterruptedException {
        long orderId = randomId();
        ConcurrentLinkedQueue<PayResultDto> paid = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> rejected = new ConcurrentLinkedQueue<>();

        runConcurrently(() -> {
            try {
                paid.add(pay(orderId, 1_000));
            } catch (DomainException e) {
                rejected.add(e.getResultCode());
            }
        });

        assertThat(paid).hasSize(1);
        assertThat(rejected).hasSize(THREADS - 1).containsOnly("409-PAYMENT_ALREADY_PAID");
        assertThat(walletRepository.findByMemberId(buyerMemberId).orElseThrow().getBalance()).isEqualTo(29_000);
    }

    private PayResultDto pay(long orderId, long amount) {
        return paymentApi.pay(new PayCommand(orderId, buyerMemberId, amount,
                LocalDateTime.now().plusMinutes(15), "TEST-" + UUID.randomUUID()));
    }

    // THREADS개 스레드가 출발 신호를 기다렸다가 같은 순간에 작업을 실행한다
    private void runConcurrently(Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }
}
