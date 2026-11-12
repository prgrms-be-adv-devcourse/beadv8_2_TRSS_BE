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

    // 결제한 PG. 승인·조회(대사)·취소를 같은 PG로 보냄
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgProvider pgProvider;

    // 충전 금액(원). 충전 준비 때 정하며 바뀌지 않음. 승인 요청 금액·PG 승인 금액과 같아야 함
    @Column(nullable = false)
    private long amount;

    // 결제에 쓰이고 남은 금액. DONE이면 이 금액 전부를 PG에 취소할 수 있음
    @Column(nullable = false)
    private long remainingAmount;

    // PG에 취소가 확정된 금액 합계. 충전 금액 전부가 취소되면 CANCELED
    @Column(nullable = false)
    private long canceledAmount;

    // PG에 취소를 요청 중인 금액. CANCELING 동안만 0보다 큼
    // 결제에 쓰인 금액 + remainingAmount + cancelingAmount + canceledAmount = amount
    @Column(nullable = false)
    private long cancelingAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TopupStatus status;

    @Column(length = 300)
    private String failReason;

    private LocalDateTime approvedAt;

    private LocalDateTime canceledAt;

    public static Topup create(Long memberId, Wallet wallet, long amount, PgProvider pgProvider) {
        Topup topup = new Topup();
        topup.memberId = memberId;
        topup.wallet = wallet;
        topup.pgProvider = pgProvider;
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

    // 결제에 필요한 금액 중 이 충전 건에서 쓸 수 있는 금액. 남은 금액이 모자라면 남은 만큼만 쓴다
    public long usableFromRemaining(long neededAmount) {
        return Math.min(neededAmount, remainingAmount);
    }

    // 결제에 쓰인 만큼 남은 금액 차감 (차감 내역은 TopupUsage로 남김)
    public void use(long usedAmount) {
        if (usedAmount <= 0) throw new IllegalArgumentException("차감 금액은 0보다 커야 합니다: " + usedAmount);
        if (status != TopupStatus.DONE) throw new IllegalStateException("승인 완료된 충전 건만 차감할 수 있습니다: " + status);
        if (usedAmount > remainingAmount) {
            throw new IllegalStateException("충전 건의 남은 금액이 부족합니다. remaining=" + remainingAmount + ", amount=" + usedAmount);
        }
        this.remainingAmount -= usedAmount;
    }

    // 결제 취소로 차감했던 남은 금액을 되돌림 (지갑 입금은 결제 취소 원장에서, 차감 내역 TopupUsage는 이력으로 남김)
    // 충전 취소 중(CANCELING)이어도 되돌리며, 진행 중인 충전 취소에는 포함되지 않고 다음 충전 취소나 결제에 쓰임
    public void restore(long restoredAmount) {
        if (restoredAmount <= 0) throw new IllegalArgumentException("복원 금액은 0보다 커야 합니다: " + restoredAmount);
        if (remainingAmount + cancelingAmount + canceledAmount + restoredAmount > amount) {
            throw new IllegalStateException("충전 금액보다 많이 복원할 수 없습니다. remaining=" + remainingAmount
                    + ", canceling=" + cancelingAmount + ", canceled=" + canceledAmount
                    + ", restore=" + restoredAmount + ", amount=" + amount);
        }
        this.remainingAmount += restoredAmount;
    }

    // 지금 PG에 취소할 수 있는 금액. DONE이면 남은 금액 전부, 아니면 0
    public long cancelableAmount() {
        return status == TopupStatus.DONE ? remainingAmount : 0;
    }

    // PG 취소 호출 전: DONE → CANCELING. 남은 금액 전부를 취소 중 금액으로 옮겨 결제 차감 대상에서 뺌 (지갑 잔액 차감은 원장에서)
    public void startCancel() {
        if (cancelableAmount() <= 0) throw new DomainException(PaymentErrorCode.TOPUP_NOT_CANCELABLE);
        this.cancelingAmount = remainingAmount;
        this.remainingAmount = 0;
        this.status = TopupStatus.CANCELING;
    }

    // PG 취소 성공: 취소 중 금액을 취소된 금액에 더함. 충전 금액 전부가 취소됐으면 CANCELED, 아니면 DONE
    public void completeCancel(LocalDateTime canceledAt) {
        if (status != TopupStatus.CANCELING) throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        this.canceledAmount += cancelingAmount;
        this.cancelingAmount = 0;
        this.canceledAt = canceledAt;
        this.status = canceledAmount == amount ? TopupStatus.CANCELED : TopupStatus.DONE;
    }

    // PG 취소 확정 실패: 취소 중 금액을 남은 금액에 더해 되돌리고 DONE (취소 중 결제 취소로 늘어난 남은 금액은 유지)
    public void failCancel() {
        if (status != TopupStatus.CANCELING) throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        this.remainingAmount += cancelingAmount;
        this.cancelingAmount = 0;
        this.status = TopupStatus.DONE;
    }

    // 충전 반영 원장(CHARGE)의 멱등 키. 같은 충전 건은 한 번만 예치금에 반영
    public String chargeIdempotencyKey() {
        return TOSS_ORDER_ID_PREFIX + getId() + "-CHARGE";
    }

    // 취소 원장(CHARGE_CANCEL)과 PG 취소 요청의 멱등 키. 취소 회차마다 달라지도록 이번 취소 전의 취소된 금액을 넣음
    public String cancelIdempotencyKey() {
        return TOSS_ORDER_ID_PREFIX + getId() + "-CANCEL-" + canceledAmount;
    }

    // 취소 확정 실패 시 되돌림 원장(CHARGE_CANCEL_RESTORE)의 멱등 키
    public String cancelRestoreIdempotencyKey() {
        return cancelIdempotencyKey() + "-RESTORE";
    }
}
