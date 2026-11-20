package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.payment.support.TestWalletCharger;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ReturnReasonCode;

class PaymentTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 10, 0);

    private final Wallet wallet = Wallet.create(3L);

    @Test
    @DisplayName("결제 준비 건은 READY이고 결제 일시가 없으며, 회원 ID는 지갑에서 가져온다")
    void ready() {
        Payment payment = Payment.ready(10L, "ORD-TEST-10", wallet, 25_000, 3_000, 2_000, "PAY-10-a");

        assertThat(payment.getOrderId()).isEqualTo(10L);
        assertThat(payment.getOrderNumber()).isEqualTo("ORD-TEST-10");
        assertThat(payment.getMemberId()).isEqualTo(3L);
        assertThat(payment.getWallet()).isSameAs(wallet);
        assertThat(payment.getAmount()).isEqualTo(25_000);
        assertThat(payment.getShippingFee()).isEqualTo(3_000);
        assertThat(payment.getCouponSubsidyAmount()).isEqualTo(2_000);
        assertThat(payment.holdingAmount()).isEqualTo(24_000);
        assertThat(payment.getIdempotencyKey()).isEqualTo("PAY-10-a");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.isPaid()).isFalse();
    }

    @Test
    @DisplayName("결제 실패 건은 FAILED_* 상태와 실패 사유가 남고 결제 일시는 없다")
    void failed() {
        Payment payment = Payment.failed(10L, "ORD-TEST-10", wallet, 25_000, "PAY-10-a",
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED_INSUFFICIENT_BALANCE);
        assertThat(payment.getOrderNumber()).isEqualTo("ORD-TEST-10");
        assertThat(payment.getFailReason()).isEqualTo("잔액 부족");
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.isPaid()).isFalse();
    }

    @Test
    @DisplayName("실패가 아닌 상태로는 실패 건을 만들 수 없다")
    void failed_notFailureStatus() {
        assertThatThrownBy(() -> Payment.failed(10L, "ORD-TEST-10", wallet, 25_000, "PAY-10-a", PaymentStatus.PAID, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 금액은 0보다 커야 한다")
    void ready_nonPositiveAmount() {
        assertThatThrownBy(() -> Payment.ready(10L, "ORD-TEST-10", wallet, 0, 0, 0, "PAY-10-a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("주문번호 없이는 결제 건을 만들 수 없다 (성공·실패 모두)")
    void orderNumberRequired() {
        assertThatThrownBy(() -> Payment.ready(10L, null, wallet, 25_000, 0, 0, "PAY-10-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.failed(10L, " ", wallet, 25_000, "PAY-10-a",
                PaymentStatus.FAILED_INSUFFICIENT_BALANCE, "잔액 부족"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FAILED_로 시작하는 상태만 실패 상태다")
    void statusIsFailure() {
        assertThat(PaymentStatus.FAILED_INSUFFICIENT_BALANCE.isFailure()).isTrue();
        assertThat(PaymentStatus.FAILED_ORDER_EXPIRED.isFailure()).isTrue();
        assertThat(PaymentStatus.FAILED_SYSTEM_ERROR.isFailure()).isTrue();
        assertThat(PaymentStatus.PAID.isFailure()).isFalse();
        assertThat(PaymentStatus.REFUNDED.isFailure()).isFalse();
    }

    @Test
    @DisplayName("결제하면 구매자 지갑에서 결제 금액이 빠지고, 배송비는 플랫폼 지갑으로, 나머지는 홀딩으로 간다")
    void pay() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 0);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, 0, "PAY-10-a");

        PaymentRecords records = payment.pay(buyer, platform, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(buyer.getBalance()).isEqualTo(5_000);
        assertThat(platform.getBalance()).isEqualTo(3_000);
        assertThat(records.transactions()).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactly(
                        tuple(WalletTransactionType.PAYMENT, -25_000L),
                        tuple(WalletTransactionType.SHIPPING_FEE, 3_000L));
        assertThat(records.holding().getAmount()).isEqualTo(22_000);
    }

    @Test
    @DisplayName("배송비가 없으면 플랫폼 지갑 없이 결제되고 결제 금액 전부가 홀딩된다")
    void pay_noShippingFee() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 0, 0, "PAY-10-a");

        PaymentRecords records = payment.pay(buyer, null, NOW);

        assertThat(records.transactions()).hasSize(1);
        assertThat(records.holding().getAmount()).isEqualTo(25_000);
    }

    @Test
    @DisplayName("준비 상태가 아니면 결제할 수 없다 (같은 결제를 두 번 결제하지 않는다)")
    void pay_twice() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 10_000, 0, 0, "PAY-10-a");
        payment.pay(buyer, null, NOW);

        assertThatThrownBy(() -> payment.pay(buyer, null, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(buyer.getBalance()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("배송비가 있는데 플랫폼 지갑이 없으면 돈을 옮기기 전에 막는다")
    void pay_shippingFeeWithoutPlatformWallet() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, 0, "PAY-10-a");

        assertThatThrownBy(() -> payment.pay(buyer, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(buyer.getBalance()).isEqualTo(30_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("결제를 취소하면 홀딩이 해제되고, 구매자에게 결제 금액 전부를, 플랫폼 지갑에서 배송비를 되돌린다")
    void cancel() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 0);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, 0, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();

        List<WalletTransaction> transactions = payment.cancel(buyer, platform, holding, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.RELEASED);
        assertThat(buyer.getBalance()).isEqualTo(30_000);
        assertThat(platform.getBalance()).isZero();
        assertThat(transactions).extracting(WalletTransaction::getType)
                .containsExactly(WalletTransactionType.PAYMENT_CANCEL, WalletTransactionType.SHIPPING_FEE_CANCEL);
    }

    @Test
    @DisplayName("정산 지급이 시작된 결제는 취소할 수 없고 돈도 움직이지 않는다")
    void cancel_afterSettlementStarted() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 0);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 3_000, 0, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();
        holding.settle(1_000, NOW);

        assertThatThrownBy(() -> payment.cancel(buyer, platform, holding, NOW))
                .isInstanceOf(DomainException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(buyer.getBalance()).isEqualTo(5_000);
        assertThat(platform.getBalance()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("쿠폰 지원금이 있으면 플랫폼 지갑에서 지원금만큼 빠져 홀딩에 더해진다")
    void pay_withCouponSubsidy() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 10_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 2_500, 3_000, "PAY-10-a");

        PaymentRecords records = payment.pay(buyer, platform, NOW);

        assertThat(buyer.getBalance()).isEqualTo(5_000);
        assertThat(platform.getBalance()).isEqualTo(9_500);
        assertThat(records.holding().getAmount()).isEqualTo(25_500);
        assertThat(records.transactions()).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactly(
                        tuple(WalletTransactionType.PAYMENT, -25_000L),
                        tuple(WalletTransactionType.SHIPPING_FEE, 2_500L),
                        tuple(WalletTransactionType.COUPON_SUBSIDY, -3_000L));
    }

    @Test
    @DisplayName("플랫폼 지갑 잔액이 쿠폰 지원금보다 적으면 돈을 옮기기 전에 PLATFORM_SUBSIDY_INSUFFICIENT로 막는다")
    void pay_platformCannotCoverSubsidy() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 2_999);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 0, 3_000, "PAY-10-a");

        assertThatThrownBy(() -> payment.pay(buyer, platform, NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PLATFORM_SUBSIDY_INSUFFICIENT");
        assertThat(buyer.getBalance()).isEqualTo(30_000);
        assertThat(platform.getBalance()).isEqualTo(2_999);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("쿠폰 지원금이 있는 결제를 취소하면 지원금은 플랫폼 지갑으로 돌아간다")
    void cancel_withCouponSubsidy() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 10_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 2_500, 3_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();

        List<WalletTransaction> transactions = payment.cancel(buyer, platform, holding, NOW);

        assertThat(buyer.getBalance()).isEqualTo(30_000);
        assertThat(platform.getBalance()).isEqualTo(10_000);
        assertThat(transactions).extracting(WalletTransaction::getType).containsExactly(
                WalletTransactionType.PAYMENT_CANCEL,
                WalletTransactionType.COUPON_SUBSIDY_CANCEL,
                WalletTransactionType.SHIPPING_FEE_CANCEL);
    }

    // ── 반품 환불 ── (설계 문서 예시: 결제 29,500 = 상품 27,000 + 배송비 2,500, 장바구니 쿠폰 지원금 3,000, 홀딩 30,000)

    @Test
    @DisplayName("불량 반품: 구매자는 배송비까지 전액, 플랫폼은 지원금을 돌려받고 처음 배송비를 내놓는다")
    void refund_defective() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 10_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 29_500, 2_500, 3_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();
        PaymentRefund refund = refundRequest(ReturnReasonCode.DEFECTIVE);

        List<WalletTransaction> transactions = payment.refund(buyer, platform, holding, refund, NOW);

        assertThat(transactions).extracting(WalletTransaction::getType, WalletTransaction::getAmount, WalletTransaction::getIdempotencyKey)
                .containsExactly(
                        tuple(WalletTransactionType.REFUND, 29_500L, "REFUND-301"),
                        tuple(WalletTransactionType.COUPON_SUBSIDY_CANCEL, 3_000L, "REFUND-301-COUPON_SUBSIDY"),
                        tuple(WalletTransactionType.SHIPPING_FEE_REFUND, -2_500L, "REFUND-301-SHIPPING_FEE"));
        assertThat(buyer.getBalance()).isEqualTo(30_000);      // 결제 전 그대로
        assertThat(platform.getBalance()).isEqualTo(10_000);   // 결제 전 그대로
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isEqualTo(NOW);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.RELEASED);
        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(refund.getAmount()).isEqualTo(29_500);
        assertThat(refund.getReturnPickupFee()).isZero();
    }

    @Test
    @DisplayName("단순 변심 반품: 구매자는 상품값만(처음 배송비 제외) 받고, 처음 배송비는 플랫폼에 남는다. 회수 택배비는 여기서 다루지 않는다")
    void refund_changeOfMind() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 10_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 29_500, 2_500, 3_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();
        PaymentRefund refund = refundRequest(ReturnReasonCode.CHANGE_OF_MIND);

        List<WalletTransaction> transactions = payment.refund(buyer, platform, holding, refund, NOW);

        assertThat(transactions).extracting(WalletTransaction::getType, WalletTransaction::getAmount, WalletTransaction::getIdempotencyKey)
                .containsExactly(
                        tuple(WalletTransactionType.REFUND, 27_000L, "REFUND-301"),
                        tuple(WalletTransactionType.COUPON_SUBSIDY_CANCEL, 3_000L, "REFUND-301-COUPON_SUBSIDY"));
        assertThat(buyer.getBalance()).isEqualTo(27_500);      // 처음 배송비 2,500원 부담
        assertThat(platform.getBalance()).isEqualTo(12_500);   // 처음 배송비 2,500원 수입
        assertThat(refund.getAmount()).isEqualTo(27_000);
        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
    }

    @Test
    @DisplayName("기타 사유·사유 없음은 불량과 같이 전액 환불한다")
    void refund_otherAndNoReasonAreFull() {
        for (ReturnReasonCode reason : new ReturnReasonCode[] {ReturnReasonCode.OTHER, null}) {
            Wallet buyer = walletWith(1L, 30_000);
            Wallet platform = walletWith(99L, 10_000);
            Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 29_500, 2_500, 3_000, "PAY-10-a");
            WalletHolding holding = payment.pay(buyer, platform, NOW).holding();
            PaymentRefund refund = refundRequest(reason);

            payment.refund(buyer, platform, holding, refund, NOW);

            assertThat(buyer.getBalance()).as("사유 %s", reason).isEqualTo(30_000);
            assertThat(refund.getReturnPickupFee()).as("사유 %s", reason).isZero();
        }
    }

    @Test
    @DisplayName("배송비·지원금이 없는 결제를 불량 반품하면 구매자 환불 한 줄만 남는다")
    void refund_withoutShippingFeeAndSubsidy() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 0, 0, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, null, NOW).holding();

        List<WalletTransaction> transactions = payment.refund(buyer, walletWith(99L, 0), holding,
                refundRequest(ReturnReasonCode.DEFECTIVE), NOW);

        assertThat(transactions).extracting(WalletTransaction::getType).containsExactly(WalletTransactionType.REFUND);
        assertThat(buyer.getBalance()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("일부라도 정산된 홀딩은 REFUND_HOLDING_SETTLED로 막고 돈·상태가 그대로다")
    void refund_holdingSettled() {
        Wallet buyer = walletWith(1L, 30_000);
        Wallet platform = walletWith(99L, 10_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 29_500, 2_500, 3_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();
        holding.settle(1_000, NOW);
        PaymentRefund refund = refundRequest(ReturnReasonCode.DEFECTIVE);

        assertThatThrownBy(() -> payment.refund(buyer, platform, holding, refund, NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-REFUND_HOLDING_SETTLED");
        assertThat(buyer.getBalance()).isEqualTo(500);
        assertThat(platform.getBalance()).isEqualTo(9_500);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.HELD);
        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.REQUESTED);
    }

    @Test
    @DisplayName("상품값이 회수 택배비보다 작은 단순 변심(2,000원 립밤)도 상품값 2,000원을 그대로 환불한다")
    void refund_changeOfMindCheapProduct() {
        Wallet buyer = walletWith(1L, 10_000);
        Wallet platform = walletWith(99L, 0);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 4_500, 2_500, 0, "PAY-10-a");   // 상품 2,000 + 배송비 2,500
        WalletHolding holding = payment.pay(buyer, platform, NOW).holding();

        List<WalletTransaction> transactions = payment.refund(buyer, platform, holding,
                refundRequest(ReturnReasonCode.CHANGE_OF_MIND), NOW);

        assertThat(transactions).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactly(tuple(WalletTransactionType.REFUND, 2_000L));
        assertThat(buyer.getBalance()).isEqualTo(7_500);
        assertThat(platform.getBalance()).isEqualTo(2_500);
    }

    @Test
    @DisplayName("플랫폼이 (배송비 − 돌려받는 지원금)도 없으면 돈을 옮기기 전에 REFUND_PLATFORM_BALANCE_INSUFFICIENT로 막는다")
    void refund_platformBalanceInsufficient() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 29_500, 2_500, 1_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, walletWith(99L, 10_000), NOW).holding();
        Wallet drainedPlatform = walletWith(99L, 1_499);   // 지원금 1,000원을 돌려받아도 2,499원이라 배송비 2,500원에 1원 모자람

        assertThatThrownBy(() -> payment.refund(buyer, drainedPlatform, holding, refundRequest(ReturnReasonCode.DEFECTIVE), NOW))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-REFUND_PLATFORM_BALANCE_INSUFFICIENT");
        assertThat(buyer.getBalance()).isEqualTo(500);
        assertThat(drainedPlatform.getBalance()).isEqualTo(1_499);
        assertThat(holding.getStatus()).isEqualTo(WalletHoldingStatus.HELD);
    }

    @Test
    @DisplayName("플랫폼이 (배송비 − 돌려받는 지원금)만큼 딱 있으면 환불된다")
    void refund_platformBalanceExact() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 29_500, 2_500, 1_000, "PAY-10-a");
        WalletHolding holding = payment.pay(buyer, walletWith(99L, 10_000), NOW).holding();
        Wallet platform = walletWith(99L, 1_500);

        payment.refund(buyer, platform, holding, refundRequest(ReturnReasonCode.DEFECTIVE), NOW);

        assertThat(platform.getBalance()).isZero();
    }

    @Test
    @DisplayName("결제 완료가 아닌 결제는 환불할 수 없다 (호출한 쪽에서 먼저 거름)")
    void refund_notPaid() {
        Wallet buyer = walletWith(1L, 30_000);
        Payment payment = Payment.ready(10L, "ORD-TEST-10", buyer, 25_000, 0, 0, "PAY-10-a");

        assertThatThrownBy(() -> payment.refund(buyer, null, null, refundRequest(ReturnReasonCode.DEFECTIVE), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    // 저장된 환불 기록처럼 ID 301을 넣는다 (원장 참조·멱등 키에 쓰임)
    private static PaymentRefund refundRequest(ReturnReasonCode reason) {
        PaymentRefund refund = PaymentRefund.request(10L, 29_500, reason);
        ReflectionTestUtils.setField(refund, "id", 301L);
        return refund;
    }

    private static Wallet walletWith(Long memberId, long balance) {
        Wallet wallet = Wallet.create(memberId);
        if (balance > 0) TestWalletCharger.charge(wallet, balance);
        return wallet;
    }
}
