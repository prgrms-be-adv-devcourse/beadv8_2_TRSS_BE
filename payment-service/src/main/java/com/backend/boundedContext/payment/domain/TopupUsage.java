package com.backend.boundedContext.payment.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 충전 차감 내역. 결제할 때 오래된 충전 건부터 차감하고, 충전 건마다 어느 결제에 얼마를 썼는지 남김 (충전 취소 가능 여부 판단용)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "topup_usage", indexes = {
        @Index(name = "idx_topup_usage_topup", columnList = "topup_id"),
        @Index(name = "idx_topup_usage_payment", columnList = "payment_id")
})
public class TopupUsage extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topup_id", nullable = false)
    private Topup topup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(nullable = false)
    private long amount;

    // 충전 건의 남은 금액을 줄이고 그 차감 내역을 만듦. 반환한 내역은 호출한 쪽에서 저장
    public static TopupUsage use(Topup topup, Payment payment, long amount) {
        topup.use(amount);

        TopupUsage usage = new TopupUsage();
        usage.topup = topup;
        usage.payment = payment;
        usage.amount = amount;
        return usage;
    }
}
