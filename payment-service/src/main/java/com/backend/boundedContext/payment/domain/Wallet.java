package com.backend.boundedContext.payment.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "wallet")
public class Wallet extends BaseIdAndTime {

    @Column(nullable = false, unique = true)
    private Long memberId;

    // 사용 가능 금액(원). 지갑을 잠금 조회(findByMemberIdForUpdate)한 뒤 change()로 바꿔 원장을 함께 남긴다
    @Column(nullable = false)
    private long balance;

    private Wallet(Long memberId) {
        this.memberId = memberId;
        this.balance = 0;
    }

    public static Wallet create(Long memberId) {
        return new Wallet(memberId);
    }

    /**
     * 잔액을 바꾸고 그 변화를 원장 한 행으로 돌려준다. 입금·차감 방향은 거래 유형이 정함
     * 잔액과 원장이 항상 같이 바뀌게 하려고 원장은 지갑이 만든다. 반환한 원장은 호출한 쪽에서 저장
     */
    public WalletTransaction change(WalletTransactionType type, long amount, Long referenceId, String idempotencyKey) {
        if (type.isDeposit()) deposit(amount);
        else withdraw(amount);
        return WalletTransaction.of(this, type, amount, referenceId, idempotencyKey);
    }

    private void deposit(long amount) {
        validatePositive(amount);
        this.balance += amount;
    }

    public boolean canPay(long amount) {
        return balance >= amount;
    }

    private void withdraw(long amount) {
        validatePositive(amount);
        if (balance < amount) {
            throw new DomainException(PaymentErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance -= amount;
    }

    private static void validatePositive(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("금액은 0보다 커야 합니다: " + amount);
    }
}
