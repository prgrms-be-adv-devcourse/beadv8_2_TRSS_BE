package com.backend.boundedContext.payment.domain;

import java.time.LocalDateTime;
import java.util.UUID;

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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "topup", indexes = @Index(name = "idx_topup_member", columnList = "member_id, created_at"))
public class Topup extends BaseIdAndTime {

    public static final String TOSS_ORDER_ID_PREFIX = "TOPUP-";

    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    // 서버가 발급한 토스 주문번호 (TOPUP-{UUID})
    @Column(nullable = false, unique = true, length = 64)
    private String tossOrderId;

    // 토스 결제키. 승인 요청 때 저장하며, 같은 결제키는 한 번만 반영
    @Column(unique = true, length = 200)
    private String paymentKey;

    @Column(nullable = false)
    private long amount;

    // 결제에 쓰이고 남은 금액. amount와 같을 때만 토스 취소 가능
    @Column(nullable = false)
    private long remainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TopupStatus status;

    @Column(length = 300)
    private String failReason;

    private LocalDateTime approvedAt;

    private LocalDateTime canceledAt;

    public static Topup create(Long memberId, Wallet wallet, long amount) {
        Topup topup = new Topup();
        topup.memberId = memberId;
        topup.wallet = wallet;
        topup.tossOrderId = TOSS_ORDER_ID_PREFIX + UUID.randomUUID();
        topup.amount = amount;
        topup.remainingAmount = 0;
        topup.status = TopupStatus.READY;
        return topup;
    }

    // 토스 승인 호출 전: READY → PROCESSING, 결제키 저장
    public void markProcessing(String paymentKey) {
        if (status != TopupStatus.READY) throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        this.paymentKey = paymentKey;
        this.status = TopupStatus.PROCESSING;
    }

    // 토스 승인 완료: PROCESSING → DONE. 이 충전 건의 남은 금액은 충전 금액 전체 (지갑 잔액 반영은 Wallet에서)
    public void approve(LocalDateTime approvedAt) {
        if (status != TopupStatus.PROCESSING) throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        this.remainingAmount = amount;
        this.approvedAt = approvedAt;
        this.status = TopupStatus.DONE;
    }

    // 실패 기록: READY·PROCESSING → FAILED_*
    public void fail(TopupStatus failStatus, String reason) {
        if (!failStatus.isFailure()) throw new IllegalArgumentException("실패 상태가 아닙니다: " + failStatus);
        if (status != TopupStatus.READY && status != TopupStatus.PROCESSING) {
            throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        }
        this.failReason = reason;
        this.status = failStatus;
    }

    // 승인 완료됐고 한 번도 쓰지 않은 충전 건만 토스 취소 가능
    public boolean isCancelable() {
        return status == TopupStatus.DONE && remainingAmount == amount;
    }
}
