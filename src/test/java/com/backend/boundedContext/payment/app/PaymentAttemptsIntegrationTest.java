package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.out.PaymentApi;

/**
 * 주문의 결제 시도 조회(PaymentApi.getPaymentAttempts)를 실제 DB로 확인한다 (오래된 순, 실패 포함, 다른 주문 제외).
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class PaymentAttemptsIntegrationTest {

    @Autowired
    private PaymentApi paymentApi;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    // 다른 테스트·시드 데이터와 겹치지 않는 회원·주문 ID
    private final Long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final Long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final Long otherOrderId = orderId + 1;

    private final List<Wallet> wallets = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll(payments);
        walletRepository.deleteAll(wallets);
    }

    @Test
    @DisplayName("주문의 결제 시도가 실패를 포함해 오래된 순으로 조회되고, 다른 주문의 시도는 빠진다")
    void getPaymentAttempts() {
        Wallet wallet = saveWallet();
        LocalDateTime paidAt = LocalDateTime.now();
        Payment failed = save(Payment.failed(orderId, wallet, 72_900, newKey(),
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족"));
        Payment paid = save(Payment.paid(orderId, wallet, 72_900, newKey(), paidAt));
        save(Payment.paid(otherOrderId, wallet, 10_000, newKey(), paidAt));

        List<PaymentAttemptDto> attempts = paymentApi.getPaymentAttempts(orderId);

        assertThat(attempts).extracting(PaymentAttemptDto::paymentId).containsExactly(failed.getId(), paid.getId());
        assertThat(attempts).extracting(PaymentAttemptDto::status)
                .containsExactly(PaymentAttemptStatus.FAILED_INSUFFICIENT_BALANCE, PaymentAttemptStatus.PAID);

        PaymentAttemptDto failedAttempt = attempts.get(0);
        assertThat(failedAttempt.amount()).isEqualTo(72_900);
        assertThat(failedAttempt.attemptedAt()).isNotNull();
        assertThat(failedAttempt.paidAt()).isNull();
        assertThat(failedAttempt.refundedAt()).isNull();

        PaymentAttemptDto paidAttempt = attempts.get(1);
        assertThat(paidAttempt.paidAt()).isNotNull();
        assertThat(paidAttempt.refundedAt()).isNull();
    }

    @Test
    @DisplayName("결제 시도가 없는 주문은 빈 목록을 돌려준다")
    void noAttempts() {
        assertThat(paymentApi.getPaymentAttempts(orderId)).isEmpty();
    }

    private Wallet saveWallet() {
        Wallet wallet = walletRepository.save(Wallet.create(memberId));
        wallets.add(wallet);
        return wallet;
    }

    private Payment save(Payment payment) {
        Payment saved = paymentRepository.save(payment);
        payments.add(saved);
        return saved;
    }

    private String newKey() {
        return "TEST-" + UUID.randomUUID();
    }
}
