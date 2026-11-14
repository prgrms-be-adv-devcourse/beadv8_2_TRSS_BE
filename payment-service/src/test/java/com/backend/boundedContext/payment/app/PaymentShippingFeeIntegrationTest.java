package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;

/**
 * 배송비가 있는 결제·결제 취소를 실제 DB로 확인한다. 로컬 Docker PostgreSQL이 필요하다.
 * 배송비는 홀딩에 넣지 않고 플랫폼(관리자) 지갑으로 옮겨야, 정산이 홀딩을 다 지급했을 때 SETTLED가 된다.
 */
@SpringBootTest(properties = "custom.payment.adminMemberId=" + PaymentShippingFeeIntegrationTest.PLATFORM_MEMBER_ID)
class PaymentShippingFeeIntegrationTest {

    // 다른 테스트·시드와 겹치지 않는 플랫폼 회원 ID
    static final long PLATFORM_MEMBER_ID = 2_100_000_001L;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    private final long buyerMemberId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final String key = "TEST-" + UUID.randomUUID();

    private Wallet buyerWallet;
    private Wallet platformWallet;
    private Long paymentId;

    @BeforeEach
    void setUp() {
        Wallet buyer = Wallet.create(buyerMemberId);
        buyer.deposit(30_000);
        buyerWallet = walletRepository.save(buyer);
        platformWallet = walletRepository.save(Wallet.create(PLATFORM_MEMBER_ID));
    }

    @AfterEach
    void cleanUp() {
        if (paymentId != null) {
            walletHoldingRepository.findAll().stream()
                    .filter(h -> h.getPayment().getId().equals(paymentId))
                    .forEach(walletHoldingRepository::delete);
            for (String suffix : new String[] {"", "-CANCEL", "-SHIPPING_FEE", "-SHIPPING_FEE-CANCEL"}) {
                walletTransactionRepository.findByIdempotencyKey("PAYMENT-" + paymentId + suffix)
                        .ifPresent(walletTransactionRepository::delete);
            }
            paymentRepository.deleteById(paymentId);
        }
        walletRepository.delete(buyerWallet);
        walletRepository.delete(platformWallet);
    }

    @Test
    @DisplayName("배송비는 플랫폼 지갑으로 가고 홀딩에는 나머지만 들어가며, 결제 취소하면 둘 다 되돌아온다")
    void payAndCancelWithShippingFee() {
        PayResultDto paid = pay(25_000, 2_500, 0);
        paymentId = paid.paymentId();

        assertThat(balance(buyerMemberId)).isEqualTo(5_000);
        assertThat(balance(PLATFORM_MEMBER_ID)).isEqualTo(2_500);
        assertThat(holding().getAmount()).isEqualTo(22_500);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getShippingFee()).isEqualTo(2_500);

        // 같은 키인데 배송비가 다르면 다른 요청이므로 409
        assertThatThrownBy(() -> pay(25_000, 0, 0))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-IDEMPOTENCY_KEY_REUSED");

        paymentFacade.cancelPayment(key);

        assertThat(balance(buyerMemberId)).isEqualTo(30_000);
        assertThat(balance(PLATFORM_MEMBER_ID)).isZero();
    }

    @Test
    @DisplayName("장바구니 쿠폰 지원금이 0이 아니거나 배송비가 결제 금액 이상이면 돈을 옮기지 않고 400")
    void rejectsUnsupportedSubsidyAndInvalidShippingFee() {
        assertThatThrownBy(() -> pay(25_000, 2_500, 1_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-INVALID_REQUEST");
        assertThatThrownBy(() -> pay(2_500, 2_500, 0))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-INVALID_REQUEST");

        assertThat(balance(buyerMemberId)).isEqualTo(30_000);
        assertThat(paymentRepository.findByIdempotencyKey(key)).isEmpty();
    }

    private PayResultDto pay(long amount, long shippingFee, long couponSubsidy) {
        return paymentFacade.pay(new PayCommand(orderId, "ORD-TEST-" + orderId, buyerMemberId, amount,
                shippingFee, couponSubsidy, LocalDateTime.now().plusMinutes(15), key));
    }

    private WalletHolding holding() {
        return walletHoldingRepository.findAll().stream()
                .filter(h -> h.getPayment().getId().equals(paymentId))
                .findFirst().orElseThrow();
    }

    private long balance(long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }
}
