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
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupUsage;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionReferenceType;
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
 * 주문 결제(pay)를 실제 DB로 확인한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class PaymentPayIntegrationTest {

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
            topupUsageRepository.deleteAll(topupUsageRepository.findAllByPayment_Id(paymentId));
            walletHoldingRepository.findAll().stream()
                    .filter(h -> h.getPayment().getId().equals(paymentId))
                    .forEach(walletHoldingRepository::delete);
            walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId)
                    .ifPresent(walletTransactionRepository::delete);
            paymentRepository.deleteById(paymentId);
        }
        topupRepository.deleteAll(topups);
        walletRepository.delete(wallet);
    }

    @Test
    @DisplayName("결제 금액만큼 오래된 충전 건부터 차감하고 차감 내역을 남긴다 (차감된 충전 건은 토스 취소 불가)")
    void pay_usesOldestTopupsFirst() {
        Topup older = doneTopup(10_000, LocalDateTime.now().minusDays(2));
        Topup newer = doneTopup(20_000, LocalDateTime.now().minusDays(1));

        PayResultDto result = pay(25_000);

        // 오래된 충전 10,000을 모두 쓰고, 다음 충전에서 15,000을 씀
        Topup olderAfter = topupRepository.findById(older.getId()).orElseThrow();
        Topup newerAfter = topupRepository.findById(newer.getId()).orElseThrow();
        assertThat(olderAfter.getRemainingAmount()).isZero();
        assertThat(newerAfter.getRemainingAmount()).isEqualTo(5_000);
        assertThat(olderAfter.isCancelable()).isFalse();
        assertThat(newerAfter.isCancelable()).isFalse();

        assertThat(topupUsageRepository.findAllByPayment_Id(result.paymentId()))
                .extracting(TopupUsage::getAmount)
                .containsExactlyInAnyOrder(10_000L, 15_000L);
    }

    @Test
    @DisplayName("충전 건으로 들어온 돈이 결제 금액보다 적으면 있는 만큼만 차감 내역을 남긴다 (나머지는 환불·정산으로 들어온 돈)")
    void pay_topupsLessThanAmount() {
        Topup only = doneTopup(10_000, LocalDateTime.now().minusDays(1));

        PayResultDto result = pay(25_000);

        assertThat(result.isPaid()).isTrue();
        assertThat(topupRepository.findById(only.getId()).orElseThrow().getRemainingAmount()).isZero();
        assertThat(topupUsageRepository.findAllByPayment_Id(result.paymentId()))
                .extracting(TopupUsage::getAmount)
                .containsExactly(10_000L);
    }

    @Test
    @DisplayName("결제가 실패하면 충전 건은 차감하지 않는다")
    void pay_failed_doesNotUseTopups() {
        Topup topup = doneTopup(10_000, LocalDateTime.now().minusDays(1));

        PayResultDto result = pay(48_000);

        assertThat(result.isPaid()).isFalse();
        assertThat(topupRepository.findById(topup.getId()).orElseThrow().getRemainingAmount()).isEqualTo(10_000);
        assertThat(topupUsageRepository.findAllByPayment_Id(result.paymentId())).isEmpty();
    }

    // 승인 완료(DONE)된 충전 건 (지갑 잔액은 setUp에서 따로 넣었으므로 여기서는 충전 건 기록만 만든다)
    private Topup doneTopup(long amount, LocalDateTime approvedAt) {
        Topup topup = Topup.create(buyerMemberId, wallet, amount);
        topup.markProcessing("TEST-PK-" + UUID.randomUUID());
        topup.approve(approvedAt);
        Topup saved = topupRepository.save(topup);
        topups.add(saved);
        return saved;
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
        assertThat(payment.getOrderNumber()).isEqualTo("ORD-TEST-" + orderId);
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

    @Test
    @DisplayName("잔액이 부족하면 예외가 아니라 FAILED_INSUFFICIENT_BALANCE를 돌려주고, 돈은 그대로이며 실패 건이 남는다")
    void pay_insufficientBalance() {
        PayResultDto result = pay(48_000);

        assertThat(result.status()).isEqualTo(PayResultStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(result.isPaid()).isFalse();
        // 잔액 30,000으로 48,000 결제 → 18,000 부족, 1만 원 단위로 올려 20,000 충전 권장
        assertThat(result.shortage()).isEqualTo(18_000);
        assertThat(result.chargeAmount()).isEqualTo(20_000);
        assertThat(balance()).isEqualTo(30_000);
        assertThat(holdingCount()).isZero();

        Payment failed = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(failed.getOrderNumber()).isEqualTo("ORD-TEST-" + orderId);
        assertThat(failed.getFailReason()).contains("잔액 부족");
        assertThat(failed.getPaidAt()).isNull();
        assertThat(walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + failed.getId())).isEmpty();
    }

    @Test
    @DisplayName("주문 결제 가능 시간이 지났으면 FAILED_ORDER_EXPIRED를 돌려주고 돈은 그대로다")
    void pay_orderExpired() {
        PayResultDto result = payWith(25_000, "TEST-" + UUID.randomUUID(), LocalDateTime.now().minusSeconds(1));

        assertThat(result.status()).isEqualTo(PayResultStatus.FAILED_ORDER_EXPIRED);
        assertThat(balance()).isEqualTo(30_000);
        assertThat(paymentRepository.findById(result.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED_ORDER_EXPIRED);
    }

    @Test
    @DisplayName("실패한 결제도 같은 멱등 키로 다시 호출하면 처음 결과(실패)를 그대로 돌려준다")
    void pay_failedReplay() {
        String key = "TEST-" + UUID.randomUUID();
        PayResultDto first = pay(40_000, key);

        PayResultDto second = pay(40_000, key);

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.status()).isEqualTo(PayResultStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(second.shortage()).isEqualTo(10_000);
        assertThat(second.chargeAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("주문 트랜잭션이 롤백돼도 결제 실패 기록은 별도 트랜잭션(REQUIRES_NEW)으로 커밋되어 남는다")
    void pay_failureRecordSurvivesRollback() {
        // 주문 쪽 흐름 흉내: 같은 트랜잭션에서 pay를 부르고, PAID가 아니면 예외로 전체 롤백
        PayResultDto result = transactionTemplate.execute(status -> {
            PayResultDto r = pay(40_000);
            status.setRollbackOnly();
            return r;
        });

        assertThat(result.status()).isEqualTo(PayResultStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(paymentRepository.findById(result.paymentId())).isPresent();
    }

    private PayResultDto pay(long amount) {
        return pay(amount, "TEST-" + UUID.randomUUID());
    }

    private PayResultDto pay(long amount, String idempotencyKey) {
        return payWith(amount, idempotencyKey, LocalDateTime.now().plusMinutes(15));
    }

    private PayResultDto payWith(long amount, String idempotencyKey, LocalDateTime orderExpiresAt) {
        PayResultDto result = paymentFacade.pay(
                new PayCommand(
                        orderId,
                        "ORD-TEST-" + orderId,
                        buyerMemberId,
                        amount,
                        orderExpiresAt,
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
