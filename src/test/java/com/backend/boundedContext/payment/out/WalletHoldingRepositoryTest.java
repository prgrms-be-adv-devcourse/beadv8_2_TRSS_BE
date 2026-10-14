package com.backend.boundedContext.payment.out;

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
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;

/**
 * 지갑의 홀딩 중 금액 합계 쿼리를 확인한다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class WalletHoldingRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    private final List<Wallet> wallets = new ArrayList<>();
    private final List<WalletHolding> holdings = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (WalletHolding holding : holdings) {
            walletHoldingRepository.deleteById(holding.getId());
            paymentRepository.deleteById(holding.getPayment().getId());
        }
        walletRepository.deleteAll(wallets);
    }

    @Test
    @DisplayName("sumRemainingAmount: 이 지갑의 HELD 홀딩만, 남은 금액(홀딩 금액 - 정산 누적 금액)으로 더한다")
    void sumRemainingAmount() {
        Wallet buyer = createWallet();
        Wallet other = createWallet();
        hold(buyer, 25_000, 0);                 // HELD, 남은 금액 25,000
        hold(buyer, 30_000, 10_000);            // 일부 정산, HELD, 남은 금액 20,000
        hold(buyer, 9_999, 9_999);              // 전부 정산 → SETTLED (제외)
        hold(other, 8_000, 0);                  // 다른 지갑 (제외)

        long held = walletHoldingRepository.sumRemainingAmount(buyer.getId(), WalletHoldingStatus.HELD);

        assertThat(held).isEqualTo(45_000);
    }

    @Test
    @DisplayName("sumRemainingAmount: 홀딩이 없으면 0")
    void sumRemainingAmount_noHolding() {
        Wallet buyer = createWallet();

        assertThat(walletHoldingRepository.sumRemainingAmount(buyer.getId(), WalletHoldingStatus.HELD)).isZero();
    }

    private Wallet createWallet() {
        // 다른 테스트·시드 회원과 겹치지 않는 회원 ID
        long memberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        Wallet wallet = walletRepository.save(Wallet.create(memberId));
        wallets.add(wallet);
        return wallet;
    }

    // 결제 완료 건과 홀딩을 만들고, settled만큼 정산된 상태로 저장한다
    private void hold(Wallet wallet, long amount, long settled) {
        long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        Payment payment = paymentRepository.save(Payment.paid(orderId, wallet, amount, "TEST-" + UUID.randomUUID(), NOW));
        WalletHolding holding = WalletHolding.hold(payment, NOW);
        if (settled > 0) holding.settle(settled, NOW);
        holdings.add(walletHoldingRepository.save(holding));
    }
}
