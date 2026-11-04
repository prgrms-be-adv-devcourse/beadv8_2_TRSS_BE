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
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.SettledOrderAmountDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.out.PaymentApi;

/**
 * 정산 지급(transfer)·지급 조회(findTransfer)를 실제 DB로 확인한다.
 * 관리자 회원 ID는 시드 관리자와 겹치지 않게 테스트용 값으로 바꾼다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest(properties = "custom.payment.adminMemberId=" + PaymentTransferIntegrationTest.ADMIN_MEMBER_ID)
class PaymentTransferIntegrationTest {

    static final long ADMIN_MEMBER_ID = 2_100_000_001L;

    @Autowired
    private PaymentApi paymentApi;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletHoldingRepository walletHoldingRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    // 다른 테스트·시드와 겹치지 않는 회원·주문·정산서 ID
    private final long buyerMemberId = randomId();
    private final long sellerMemberId = randomId();
    private final long orderId1 = randomId();
    private final long orderId2 = randomId();
    private final long settlementId = randomId();
    private final String idempotencyKey = "SETTLEMENT-" + settlementId;

    private Wallet buyerWallet;
    private Wallet sellerWallet;
    private Wallet adminWallet;
    private long adminBalanceBefore;
    private final List<WalletHolding> holdings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        buyerWallet = walletRepository.save(Wallet.create(buyerMemberId));
        sellerWallet = walletRepository.save(Wallet.create(sellerMemberId));
        adminWallet = walletRepository.findByMemberId(ADMIN_MEMBER_ID)
                .orElseGet(() -> walletRepository.save(Wallet.create(ADMIN_MEMBER_ID)));
        adminBalanceBefore = adminWallet.getBalance();

        hold(orderId1, 25_000);
        hold(orderId2, 9_999);
    }

    @AfterEach
    void cleanUp() {
        for (String suffix : List.of("-PAYOUT", "-FEE")) {
            walletTransactionRepository.findByIdempotencyKey(idempotencyKey + suffix)
                    .ifPresent(walletTransactionRepository::delete);
        }
        for (WalletHolding holding : holdings) {
            walletHoldingRepository.deleteById(holding.getId());
            paymentRepository.deleteById(holding.getPayment().getId());
        }
        walletRepository.findByMemberId(ADMIN_MEMBER_ID).ifPresent(walletRepository::delete);
        walletRepository.delete(sellerWallet);
        walletRepository.delete(buyerWallet);
    }

    @Test
    @DisplayName("홀딩에서 판매자 지갑(SETTLEMENT)·관리자 지갑(FEE)으로 입금하고, 다 정산된 홀딩은 SETTLED가 된다")
    void transfer() {
        // 판매가 34,999원(25,000 + 9,999), 수수료 3,499원, 지급액 31,500원
        TransferResultDto result = paymentApi.transfer(command(31_500, 3_499,
                new SettledOrderAmountDto(orderId1, 25_000), new SettledOrderAmountDto(orderId2, 9_999)));

        assertThat(balance(sellerMemberId)).isEqualTo(31_500);
        assertThat(balance(ADMIN_MEMBER_ID)).isEqualTo(adminBalanceBefore + 3_499);

        WalletHolding holding1 = holding(orderId1);
        assertThat(holding1.getStatus()).isEqualTo(WalletHoldingStatus.SETTLED);
        assertThat(holding1.getSettledAmount()).isEqualTo(25_000);
        assertThat(holding1.getSettledAt()).isNotNull();
        assertThat(holding(orderId2).getStatus()).isEqualTo(WalletHoldingStatus.SETTLED);

        WalletTransaction payout = walletTransactionRepository.findByIdempotencyKey(idempotencyKey + "-PAYOUT").orElseThrow();
        WalletTransaction fee = walletTransactionRepository.findByIdempotencyKey(idempotencyKey + "-FEE").orElseThrow();
        assertThat(payout.getType()).isEqualTo(WalletTransactionType.SETTLEMENT);
        assertThat(payout.getAmount()).isEqualTo(31_500);
        assertThat(payout.getReferenceId()).isEqualTo(settlementId);
        assertThat(fee.getType()).isEqualTo(WalletTransactionType.FEE);
        assertThat(fee.getAmount()).isEqualTo(3_499);

        assertThat(result.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(result.walletTransactionId()).isEqualTo(payout.getId());
        assertThat(result.payoutAmount()).isEqualTo(31_500);
        assertThat(result.feeAmount()).isEqualTo(3_499);
        assertThat(result.transferredAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 호출하면 다시 지급하지 않고 처음 결과를 돌려준다")
    void transfer_idempotent() {
        TransferResultDto first = paymentApi.transfer(command(22_500, 2_500,
                new SettledOrderAmountDto(orderId1, 25_000)));

        TransferResultDto second = paymentApi.transfer(command(22_500, 2_500,
                new SettledOrderAmountDto(orderId1, 25_000)));

        assertThat(second.walletTransactionId()).isEqualTo(first.walletTransactionId());
        assertThat(second.payoutAmount()).isEqualTo(22_500);
        assertThat(second.feeAmount()).isEqualTo(2_500);
        assertThat(balance(sellerMemberId)).isEqualTo(22_500);
        assertThat(holding(orderId1).getSettledAmount()).isEqualTo(25_000);
    }

    @Test
    @DisplayName("주문 금액 일부만 정산하면 정산 누적 금액만 늘고 홀딩은 HELD로 남는다")
    void transfer_partial() {
        paymentApi.transfer(command(9_000, 1_000, new SettledOrderAmountDto(orderId1, 10_000)));

        WalletHolding holding = holding(orderId1);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.HELD);
        assertThat(holding.getSettledAmount()).isEqualTo(10_000);
        assertThat(holding.remainingAmount()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("수수료가 0원이면 FEE 원장을 남기지 않는다")
    void transfer_zeroFee() {
        TransferResultDto result = paymentApi.transfer(command(9, 0, new SettledOrderAmountDto(orderId2, 9)));

        assertThat(result.feeAmount()).isZero();
        assertThat(walletTransactionRepository.findByIdempotencyKey(idempotencyKey + "-FEE")).isEmpty();
        assertThat(balance(ADMIN_MEMBER_ID)).isEqualTo(adminBalanceBefore);
    }

    @Test
    @DisplayName("주문별 금액 합계가 지급액 + 수수료와 다르면 409 TRANSFER_AMOUNT_MISMATCH")
    void transfer_amountMismatch() {
        assertThatThrownBy(() -> paymentApi.transfer(command(22_500, 2_000,
                new SettledOrderAmountDto(orderId1, 25_000))))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-TRANSFER_AMOUNT_MISMATCH");

        assertThat(balance(sellerMemberId)).isZero();
        assertThat(holding(orderId1).getSettledAmount()).isZero();
    }

    @Test
    @DisplayName("홀딩이 없는 주문이 있으면 404 HOLDING_NOT_FOUND이고 아무것도 바뀌지 않는다")
    void transfer_holdingNotFound() {
        long unknownOrderId = randomId();

        assertThatThrownBy(() -> paymentApi.transfer(command(31_500, 3_500,
                new SettledOrderAmountDto(orderId1, 25_000), new SettledOrderAmountDto(unknownOrderId, 10_000))))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-HOLDING_NOT_FOUND");

        assertThat(holding(orderId1).getSettledAmount()).isZero();
        assertThat(balance(sellerMemberId)).isZero();
    }

    @Test
    @DisplayName("홀딩 남은 금액보다 많이 지급하면 409 HOLDING_INSUFFICIENT이고 앞 주문의 정산도 롤백된다")
    void transfer_holdingInsufficient() {
        assertThatThrownBy(() -> paymentApi.transfer(command(31_500, 3_500,
                new SettledOrderAmountDto(orderId1, 25_000), new SettledOrderAmountDto(orderId2, 10_000))))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-HOLDING_INSUFFICIENT");

        assertThat(holding(orderId1).getSettledAmount()).isZero();
        assertThat(holding(orderId1).getStatus()).isEqualTo(WalletHoldingStatus.HELD);
        assertThat(balance(sellerMemberId)).isZero();
    }

    @Test
    @DisplayName("판매자 지갑이 없으면 404 WALLET_NOT_FOUND이고 홀딩 정산도 롤백된다")
    void transfer_walletNotFound() {
        long noWalletMemberId = randomId();

        assertThatThrownBy(() -> paymentApi.transfer(new SettlementTransferCommand(idempotencyKey, settlementId,
                noWalletMemberId, 22_500, 2_500, List.of(new SettledOrderAmountDto(orderId1, 25_000)))))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-WALLET_NOT_FOUND");

        assertThat(holding(orderId1).getSettledAmount()).isZero();
    }

    @Test
    @DisplayName("지급한 정산은 findTransfer로 같은 결과를 조회할 수 있다")
    void findTransfer() {
        TransferResultDto transferred = paymentApi.transfer(command(22_500, 2_500,
                new SettledOrderAmountDto(orderId1, 25_000)));

        TransferResultDto found = paymentApi.findTransfer(idempotencyKey).orElseThrow();

        assertThat(found.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(found.walletTransactionId()).isEqualTo(transferred.walletTransactionId());
        assertThat(found.payoutAmount()).isEqualTo(22_500);
        assertThat(found.feeAmount()).isEqualTo(2_500);
        assertThat(found.transferredAt()).isNotNull();
    }

    @Test
    @DisplayName("지급하지 않은 멱등 키는 empty")
    void findTransfer_empty() {
        assertThat(paymentApi.findTransfer("SETTLEMENT-" + UUID.randomUUID())).isEmpty();
    }

    private SettlementTransferCommand command(long payoutAmount, long feeAmount, SettledOrderAmountDto... orders) {
        return new SettlementTransferCommand(idempotencyKey, settlementId, sellerMemberId,
                payoutAmount, feeAmount, List.of(orders));
    }

    // 결제 완료 건과 HELD 홀딩을 만든다 (pay 실제 구현 전이라 직접 저장)
    private void hold(long orderId, long amount) {
        Payment payment = paymentRepository.save(Payment.paid(orderId, "ORD-TEST-" + orderId, buyerWallet, amount,
                "TEST-" + UUID.randomUUID(), LocalDateTime.now()));
        holdings.add(walletHoldingRepository.save(WalletHolding.hold(payment, LocalDateTime.now())));
    }

    private WalletHolding holding(long orderId) {
        return holdings.stream()
                .filter(h -> h.getOrderId() == orderId)
                .map(h -> walletHoldingRepository.findById(h.getId()).orElseThrow())
                .findFirst().orElseThrow();
    }

    private long balance(long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }
}
