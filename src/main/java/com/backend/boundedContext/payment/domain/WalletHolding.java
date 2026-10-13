package com.backend.boundedContext.payment.domain;

import java.time.LocalDateTime;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 예치금 홀딩. 결제한 돈을 판매자에게 지급하기 전까지 플랫폼이 맡아 두는 칸 (주문서 1개당 1개)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "wallet_holding", indexes = @Index(name = "idx_wallet_holding_wallet", columnList = "wallet_id, status"))
public class WalletHolding extends BaseIdAndTime {

    // 주문당 홀딩 1개. 같은 주문의 PAID 결제가 두 번 생기는 것도 이 유니크로 막힘
    @Column(nullable = false, unique = true)
    private Long orderId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false, unique = true)
    private Payment payment;

    // 구매자 지갑
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false)
    private long amount;

    // 판매자별 정산 지급 누적. amount와 같아지면 SETTLED
    @Column(nullable = false)
    private long settledAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletHoldingStatus status;

    @Column(nullable = false)
    private LocalDateTime heldAt;

    private LocalDateTime releasedAt;

    private LocalDateTime settledAt;

    // 결제 성공 건의 금액 전체를 홀딩
    public static WalletHolding hold(Payment payment, LocalDateTime heldAt) {
        if (!payment.isPaid()) throw new IllegalStateException("결제 완료 건만 홀딩할 수 있습니다: " + payment.getStatus());
        WalletHolding holding = new WalletHolding();
        holding.orderId = payment.getOrderId();
        holding.payment = payment;
        holding.wallet = payment.getWallet();
        holding.amount = payment.getAmount();
        holding.settledAmount = 0;
        holding.status = WalletHoldingStatus.HELD;
        holding.heldAt = heldAt;
        return holding;
    }

    // 아직 지급하지 않은 금액
    public long remainingAmount() {
        return amount - settledAmount;
    }

    // 정산 지급: 이번 지급액만큼 누적하고, 다 차면 SETTLED (HELD인지는 호출한 쪽에서 먼저 확인)
    public void settle(long settleAmount, LocalDateTime now) {
        if (settleAmount <= 0) throw new IllegalArgumentException("지급 금액은 0보다 커야 합니다: " + settleAmount);
        if (status != WalletHoldingStatus.HELD) throw new IllegalStateException("HELD 홀딩만 정산할 수 있습니다: " + status);
        if (settleAmount > remainingAmount()) throw new DomainException(PaymentErrorCode.HOLDING_INSUFFICIENT);

        this.settledAmount += settleAmount;
        if (settledAmount == amount) {
            this.status = WalletHoldingStatus.SETTLED;
            this.settledAt = now;
        }
    }
}
