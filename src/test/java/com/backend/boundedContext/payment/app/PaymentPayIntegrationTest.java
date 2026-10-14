package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionReferenceType;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;

/**
 * 주문 결제(pay)를 실제 DB로 확인한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class PaymentPayIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    // 다른 테스트·시드와 겹치지 않는 회원·주문 ID
    private final long buyerMemberId = randomId();
    private final long orderId = randomId();

    private Wallet wallet;
    private final List<Long> paymentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Wallet created = Wallet.create(buyerMemberId);
        created.deposit(30_000);
        wallet = walletRepository.save(created);
    }

    @AfterEach
    void cleanUp() {
        for (Long paymentId : paymentIds) {
            walletHoldingRepository.findAll().stream()
                    .filter(h -> h.getPayment().getId().equals(paymentId))
                    .forEach(walletHoldingRepository::delete);
            walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId)
                    .ifPresent(walletTransactionRepository::delete);
            paymentRepository.deleteById(paymentId);
        }
        walletRepository.delete(wallet);
    }

    @Test
    @DisplayName("잔액이 충분하면 지갑에서 홀딩으로 옮기고, 결제 PAID·원장 PAYMENT·홀딩 HELD를 남긴다")
    void pay_success() {
        PayResultDto result = pay(25_000);

        // 결과
        assertThat(result.status()).isEqualTo(PayResultStatus.PAID);
        assertThat(result.isPaid()).isTrue();
        assertThat(result.shortage()).isZero();
        assertThat(result.chargeAmount()).isZero();

        // 지갑: 30,000 → 5,000
        assertThat(walletRepository.findByMemberId(buyerMemberId).orElseThrow().getBalance()).isEqualTo(5_000);

        // 결제 건
        Payment payment = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getOrderId()).isEqualTo(orderId);
        assertThat(payment.getMemberId()).isEqualTo(buyerMemberId);
        assertThat(payment.getAmount()).isEqualTo(25_000);
        assertThat(payment.getPaidAt()).isNotNull();

        // 원장: 결제 ID를 가리키는 PAYMENT -25,000, 거래 후 잔액 5,000
        WalletTransaction ledger = walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + payment.getId()).orElseThrow();
        assertThat(ledger.getType()).isEqualTo(WalletTransactionType.PAYMENT);
        assertThat(ledger.getAmount()).isEqualTo(-25_000);
        assertThat(ledger.getBalanceAfter()).isEqualTo(5_000);
        assertThat(ledger.getReferenceType()).isEqualTo(WalletTransactionReferenceType.PAYMENT);
        assertThat(ledger.getReferenceId()).isEqualTo(payment.getId());

        // 홀딩: 주문의 결제 금액 전체가 HELD
        WalletHolding holding = walletHoldingRepository.findAll().stream()
                .filter(h -> h.getOrderId().equals(orderId))
                .findFirst().orElseThrow();
        assertThat(holding.getAmount()).isEqualTo(25_000);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.HELD);

        // 지갑 조회에도 반영: 잔액 5,000 / 홀딩 중 25,000
        assertThat(paymentFacade.getWallet(buyerMemberId).heldAmount()).isEqualTo(25_000);
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 호출하면 처음 결과를 그대로 돌려주고 돈은 한 번만 빠진다")
    void pay_sameKey_replay() {
        String key = "TEST-" + UUID.randomUUID();
        PayResultDto first = pay(25_000, key);

        PayResultDto second = pay(25_000, key);

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.status()).isEqualTo(PayResultStatus.PAID);
        assertThat(balance()).isEqualTo(5_000);
        assertThat(holdingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 멱등 키로 다른 금액을 보내면 409 IDEMPOTENCY_KEY_REUSED이고 돈은 그대로다")
    void pay_sameKey_differentRequest() {
        String key = "TEST-" + UUID.randomUUID();
        pay(25_000, key);

        assertThatThrownBy(() -> pay(20_000, key))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-IDEMPOTENCY_KEY_REUSED");
        assertThat(balance()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("이미 PAID인 주문에 새 멱등 키로 결제하면 409 PAYMENT_ALREADY_PAID이고 다시 결제하지 않는다")
    void pay_alreadyPaidOrder() {
        pay(10_000);

        assertThatThrownBy(() -> pay(10_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PAYMENT_ALREADY_PAID");
        assertThat(balance()).isEqualTo(20_000);
        assertThat(holdingCount()).isEqualTo(1);
    }

    private PayResultDto pay(long amount) {
        return pay(amount, "TEST-" + UUID.randomUUID());
    }

    private PayResultDto pay(long amount, String idempotencyKey) {
        PayResultDto result = paymentFacade.pay(
                new PayCommand(
                        orderId,
                        buyerMemberId,
                        amount,
                        LocalDateTime.now().plusMinutes(15),
                        idempotencyKey
                )
        );
        if (!paymentIds.contains(result.paymentId())) paymentIds.add(result.paymentId());
        return result;
    }

    private long balance() {
        return walletRepository.findByMemberId(buyerMemberId).orElseThrow().getBalance();
    }

    private long holdingCount() {
        return walletHoldingRepository.findAll().stream().filter(h -> h.getOrderId().equals(orderId)).count();
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }
}
