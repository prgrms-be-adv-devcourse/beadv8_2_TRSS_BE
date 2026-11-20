package com.backend.boundedContext.payment.domain;

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

// 예치금 원장. 지갑 잔액이 바뀔 때마다 한 행씩 남김
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "wallet_transaction", indexes = {
        @Index(name = "idx_wallet_tx_wallet", columnList = "wallet_id, created_at"),
        @Index(name = "idx_wallet_tx_reference", columnList = "reference_type, reference_id")
})
public class WalletTransaction extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;  // 지갑 ID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WalletTransactionType type;  // 유형

    // 지갑 기준 증감. 입금은 +, 차감은 -
    @Column(nullable = false)
    private long amount;  // 금액

    @Column(nullable = false)
    private long balanceAfter;  // 거래 후 잔액

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WalletTransactionReferenceType referenceType;  // 참조 유형

    @Column(nullable = false)
    private Long referenceId;  // 참조 ID

    // 같은 잔액 변화가 두 번 기록되지 않게 막는 키 (예: TOPUP-5-CHARGE, SETTLEMENT-31-PAYOUT)
    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;  // 멱등 키

    // shortcut: 충전 처리기가 아직 이 메서드를 쓴다. 충전 리팩토링 때 wallet.change로 바꾸고 지운다
    public static WalletTransaction record(Wallet wallet, WalletTransactionType type, long amount, Long referenceId, String idempotencyKey) {
        return wallet.change(type, amount, referenceId, idempotencyKey);
    }

    static WalletTransaction of(Wallet wallet, WalletTransactionType type, long amount, Long referenceId, String idempotencyKey) {
        WalletTransaction transaction = new WalletTransaction();
        transaction.wallet = wallet;
        transaction.type = type;
        transaction.amount = type.isDeposit() ? amount : -amount;
        transaction.balanceAfter = wallet.getBalance();
        transaction.referenceType = type.getReferenceType();
        transaction.referenceId = referenceId;
        transaction.idempotencyKey = idempotencyKey;
        return transaction;
    }
}
