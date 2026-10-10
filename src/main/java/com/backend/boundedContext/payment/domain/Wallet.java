package com.backend.boundedContext.payment.domain;

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

    // 사용 가능 금액(원). 잔액 변경은 원장 기록과 함께 충전·결제 기능에서 추가
    @Column(nullable = false)
    private long balance;

    private Wallet(Long memberId) {
        this.memberId = memberId;
        this.balance = 0;
    }

    public static Wallet create(Long memberId) {
        return new Wallet(memberId);
    }
}
