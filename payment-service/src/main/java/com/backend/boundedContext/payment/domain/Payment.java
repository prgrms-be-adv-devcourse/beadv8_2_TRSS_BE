package com.backend.boundedContext.payment.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 주문 결제 시도 1건. 한 주문에 시도가 여러 건 생길 수 있고, PAID는 주문당 1건 (홀딩의 order_id 유니크로 보장)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment", indexes = {
        @Index(name = "idx_payment_order", columnList = "order_id, created_at"),
        @Index(name = "idx_payment_order_number", columnList = "order_number")
})
public class Payment extends BaseIdAndTime {

    @Column(nullable = false)
    private Long orderId;

    // 주문번호 (관리자 결제 조회 표시·검색용). 주문번호는 바뀌지 않아 결제 시점 값을 그대로 둔다
    @Column(nullable = false, length = 40)
    private String orderNumber;

    // 구매자 회원 ID
    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false)
    private long amount;

    // amount 중 배송비. 홀딩에 넣지 않고 플랫폼(관리자) 지갑으로 옮긴다
    @Column(nullable = false)
    private long shippingFee;

    // 장바구니 쿠폰 지원금(플랫폼 부담)
    // 홀딩 금액 = amount - shippingFee + couponSubsidyAmount
    @Column(nullable = false)
    private long couponSubsidyAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentStatus status;

    @Column(length = 300)
    private String failReason;

    // 주문 컨텍스트가 넘긴 멱등 키. 같은 키로 다시 호출하면 이 결제 건의 결과를 그대로 반환
    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    private LocalDateTime paidAt;

    private LocalDateTime refundedAt;

    private LocalDateTime canceledAt;

    public static Payment ready(
            Long orderId,
            String orderNumber,
            Wallet wallet,
            long amount,
            long shippingFee,
            long couponSubsidyAmount,
            String idempotencyKey
    ) {
        if (couponSubsidyAmount < 0) {
            throw new IllegalArgumentException("쿠폰 지원금은 0 이상이어야 합니다: " + couponSubsidyAmount);
        }
        Payment payment = create(
                orderId,
                orderNumber,
                wallet,
                amount,
                idempotencyKey,
                PaymentStatus.READY
        ).withShippingFee(shippingFee);
        payment.couponSubsidyAmount = couponSubsidyAmount;
        return payment;
    }

    /**
     * 결제: 구매자 지갑에서 결제 금액을 빼고, 배송비는 플랫폼 지갑으로 보내고, 쿠폰 지원금은 플랫폼 지갑에서 홀딩으로 채운다
     * 지갑은 잠금 조회한 것을 넘긴다. 배송비·지원금이 모두 0이면 플랫폼 지갑은 null이어도 된다
     * 플랫폼 지갑 잔액이 지원금보다 적으면 돈을 옮기기 전에 막는다 (구매자 잔액 부족 402와 구분)
     */
    public PaymentRecords pay(Wallet buyerWallet, Wallet platformWallet, LocalDateTime now) {
        if (status != PaymentStatus.READY) throw new IllegalStateException("준비 상태의 결제만 결제할 수 있습니다: " + status);
        rejectIfPlatformWalletMissing(platformWallet);
        if (hasCouponSubsidy() && !platformWallet.canPay(couponSubsidyAmount)) {
            throw new DomainException(PaymentErrorCode.PLATFORM_SUBSIDY_INSUFFICIENT);
        }

        List<WalletTransaction> transactions = new ArrayList<>();
        transactions.add(buyerWallet.change(
                WalletTransactionType.PAYMENT,
                amount,
                getId(),
                paymentIdempotencyKey()
        ));
        if (hasShippingFee()) {
            transactions.add(platformWallet.change(
                    WalletTransactionType.SHIPPING_FEE,
                    shippingFee,
                    getId(),
                    shippingFeeIdempotencyKey()
            ));
        }
        if (hasCouponSubsidy()) {
            transactions.add(platformWallet.change(
                    WalletTransactionType.COUPON_SUBSIDY,
                    couponSubsidyAmount,
                    getId(),
                    couponSubsidyIdempotencyKey()
            ));
        }
        this.status = PaymentStatus.PAID;
        this.paidAt = now;
        return new PaymentRecords(transactions, WalletHolding.hold(this, now));
    }

    /**
     * 결제 취소: pay()를 거꾸로 한다. 홀딩 해제 → 구매자 환급 → 쿠폰 지원금을 플랫폼 지갑으로 반환 → 플랫폼 지갑에서 배송비 회수
     * 정산 지급이 시작된 홀딩이면 해제에서 409로 막혀 돈이 움직이지 않는다. 반환한 원장은 호출한 쪽에서 저장
     */
    public List<WalletTransaction> cancel(
            Wallet buyerWallet,
            Wallet platformWallet,
            WalletHolding holding,
            LocalDateTime now
    ) {
        if (!isPaid()) throw new DomainException(PaymentErrorCode.PAYMENT_NOT_CANCELABLE);
        rejectIfPlatformWalletMissing(platformWallet);
        holding.release(now);

        this.status = PaymentStatus.CANCELED;
        this.canceledAt = now;

        List<WalletTransaction> transactions = new ArrayList<>();
        transactions.add(buyerWallet.change(
                WalletTransactionType.PAYMENT_CANCEL,
                amount,
                getId(),
                cancelIdempotencyKey()
        ));
        // 지원금을 먼저 돌려줘서, 배송비를 회수할 때 플랫폼 지갑 잔액이 모자라지 않게 한다
        if (hasCouponSubsidy()) {
            transactions.add(platformWallet.change(
                    WalletTransactionType.COUPON_SUBSIDY_CANCEL,
                    couponSubsidyAmount,
                    getId(),
                    couponSubsidyCancelIdempotencyKey()
            ));
        }
        if (hasShippingFee()) {
            transactions.add(platformWallet.change(
                    WalletTransactionType.SHIPPING_FEE_CANCEL,
                    shippingFee,
                    getId(),
                    shippingFeeCancelIdempotencyKey()
            ));
        }
        return transactions;
    }

    // 반품 환불: 홀딩 해제하고 반품 사유에 따라 로직이 달라진다.
    // - 단순 변심인 경우: [구매자에게 결제 금액 − 처음 배송비](상품값)를 돌려준다. 처음 배송비는 결제 때 이미 플랫폼에 있으므로 그대로 둔다.
    //   반품 회수 택배비는 여기서 다루지 않는다. 구매자가 따로 결제한 뒤에 이 환불을 부른다
    // - 불량, 기타인 경우: [구매자에게 결제 금액 전부]를 돌려준다. 플랫폼 지갑에서 처음 배송비를 뺀다(SHIPPING_FEE_REFUND)
    // 쿠폰 지원금은 사유와 상관없이 플랫폼으로 돌아간다(COUPON_SUBSIDY_CANCEL)
    // 정산이 시작된 홀딩이거나 플랫폼 잔액이 부족하면 DomainException으로 막는다
    public List<WalletTransaction> refund(
            Wallet buyerWallet,
            Wallet platformWallet,
            WalletHolding holding,
            PaymentRefund paymentRefund,
            LocalDateTime now
    ) {
        if (!isPaid()) throw new IllegalStateException("결제 완료 건만 환불할 수 있습니다: " + status);
        if (!holding.isReleasable()) throw new DomainException(PaymentErrorCode.REFUND_HOLDING_SETTLED);

        boolean changeOfMind = paymentRefund.isChangeOfMind();
        long buyerAmount = refundAmount(changeOfMind);

        boolean shouldRefundShippingFee = !changeOfMind && hasShippingFee();
        // 지원금을 먼저 돌려받으므로, 플랫폼은 (배송비 − 지원금)만 있으면 배송비를 내놓을 수 있다
        if (shouldRefundShippingFee && !platformWallet.canPay(shippingFee - couponSubsidyAmount)) {
            throw new DomainException(PaymentErrorCode.REFUND_PLATFORM_BALANCE_INSUFFICIENT);
        }

        holding.release(now);
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = now;
        paymentRefund.complete(getId(), buyerAmount, now);

        List<WalletTransaction> transactions = new ArrayList<>();
        transactions.add(buyerWallet.change(
                WalletTransactionType.REFUND, buyerAmount, paymentRefund.getId(), paymentRefund.idempotencyKey()));
        if (hasCouponSubsidy()) {
            transactions.add(platformWallet.change(
                    WalletTransactionType.COUPON_SUBSIDY_CANCEL, couponSubsidyAmount, getId(),
                    paymentRefund.couponSubsidyIdempotencyKey()));
        }
        if (shouldRefundShippingFee) {
            transactions.add(platformWallet.change(
                    WalletTransactionType.SHIPPING_FEE_REFUND, shippingFee, paymentRefund.getId(),
                    paymentRefund.shippingFeeIdempotencyKey()));
        }
        return transactions;
    }

    // 구매자가 돌려받는 금액: 단순 변심이면 상품값만(결제 금액 − 처음 배송비), 그 밖에는 결제 금액 전부
    // 결제 때 배송비 < 결제 금액을 검증하므로 늘 0보다 크다
    public long refundAmount(boolean changeOfMind) {
        return changeOfMind ? amount - shippingFee : amount;
    }

    private void rejectIfPlatformWalletMissing(Wallet platformWallet) {
        if (needsPlatformWallet() && platformWallet == null) {
            throw new IllegalArgumentException("배송비·쿠폰 지원금이 있는 결제는 플랫폼 지갑이 필요합니다: shippingFee="
                    + shippingFee + ", couponSubsidyAmount=" + couponSubsidyAmount);
        }
    }

    // 결제 실패 건 (FAILED_*). 결제 트랜잭션과 별도 트랜잭션으로 저장
    public static Payment failed(
            Long orderId,
            String orderNumber,
            Wallet wallet,
            long amount,
            String idempotencyKey,
            PaymentStatus failStatus,
            String failReason
    ) {
        if (!failStatus.isFailure()) throw new IllegalArgumentException("실패 상태가 아닙니다: " + failStatus);
        Payment payment = create(
                orderId,
                orderNumber,
                wallet,
                amount,
                idempotencyKey,
                failStatus
        );
        payment.failReason = failReason;
        return payment;
    }

    private static Payment create(
            Long orderId,
            String orderNumber,
            Wallet wallet,
            long amount,
            String idempotencyKey,
            PaymentStatus status
    ) {
        if (amount <= 0) throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다: " + amount);
        if (orderNumber == null || orderNumber.isBlank()) throw new IllegalArgumentException("주문번호가 없습니다: orderId=" + orderId);

        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.orderNumber = orderNumber;
        payment.memberId = wallet.getMemberId();
        payment.wallet = wallet;
        payment.amount = amount;
        payment.idempotencyKey = idempotencyKey;
        payment.status = status;
        return payment;
    }

    // 결제 금액 중 배송비를 기록한다 (저장 전에 부른다). 배송비만으로 된 결제는 홀딩이 0원이 되므로 막는다
    public Payment withShippingFee(long shippingFee) {
        if (shippingFee < 0 || shippingFee >= amount) {
            throw new IllegalArgumentException("배송비는 0 이상, 결제 금액 미만이어야 합니다: " + shippingFee);
        }
        this.shippingFee = shippingFee;
        return this;
    }

    // 홀딩에 들어가는 금액 = 결제액 - 배송비 + 쿠폰 지원금
    public long holdingAmount() {
        return amount - shippingFee + couponSubsidyAmount;
    }

    // 배송비나 쿠폰 지원금이 있으면 결제·취소할 때 플랫폼 지갑도 잠가서 넘겨야 한다
    public boolean needsPlatformWallet() {
        return hasShippingFee() || hasCouponSubsidy();
    }

    private boolean hasShippingFee() {
        return shippingFee > 0;
    }

    private boolean hasCouponSubsidy() {
        return couponSubsidyAmount > 0;
    }

    // 쿠폰 지원금 원장(COUPON_SUBSIDY)·지원금 반환 원장(COUPON_SUBSIDY_CANCEL)의 멱등 키
    public String couponSubsidyIdempotencyKey() {
        return "PAYMENT-" + getId() + "-COUPON_SUBSIDY";
    }

    public String couponSubsidyCancelIdempotencyKey() {
        return "PAYMENT-" + getId() + "-COUPON_SUBSIDY-CANCEL";
    }

    // 배송비 원장(SHIPPING_FEE)·배송비 취소 원장(SHIPPING_FEE_CANCEL)의 멱등 키
    public String shippingFeeIdempotencyKey() {
        return "PAYMENT-" + getId() + "-SHIPPING_FEE";
    }

    public String shippingFeeCancelIdempotencyKey() {
        return "PAYMENT-" + getId() + "-SHIPPING_FEE-CANCEL";
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }

    public boolean isCanceled() {
        return status == PaymentStatus.CANCELED;
    }

    // 실패 건(FAILED_*): 돈이 움직이지 않은 결제 시도
    public boolean isFailed() {
        return status.isFailure();
    }

    // 결제 원장(PAYMENT)의 멱등 키
    public String paymentIdempotencyKey() {
        return "PAYMENT-" + getId();
    }

    // 결제 취소 원장(PAYMENT_CANCEL)의 멱등 키
    public String cancelIdempotencyKey() {
        return "PAYMENT-" + getId() + "-CANCEL";
    }
}
