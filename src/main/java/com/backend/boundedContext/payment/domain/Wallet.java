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

    // 사용 가능 금액(원). 변경할 때는 지갑을 잠금 조회(findByMemberIdForUpdate)한 뒤 원장(wallet_transaction)과 함께 기록
    @Column(nullable = false)
    private long balance;

    private Wallet(Long memberId) {
        this.memberId = memberId;
        this.balance = 0;
    }

    public static Wallet create(Long memberId) {
        return new Wallet(memberId);
    }

    // 잔액 증가 (충전·환불·정산 입금)
    public void deposit(long amount) {
        validatePositive(amount);
        this.balance += amount;
    }

    // 잔액 차감 (결제·충전 취소). 잔액 부족은 사용자가 만들 수 있는 실패라 402 INSUFFICIENT_BALANCE로 막음
    public void withdraw(long amount) {
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
