package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 정산서
 *
 * 판매자 1명 × 정산월 1개 = 1건이며, 지급(3구간)의 처리 단위다.
 * 상태는 엔티티 메서드로만 바꾸고, 잘못된 전이는 409 SETTLEMENT_STATE_INVALID로 막는다.
 * 단, 지급 선점(READY·FAILED → PROCESSING)은 동시성 때문에 Repository의 조건부 UPDATE로 한다.
 */
@Entity
@Table(name = "settlement",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_settlement_seller_month", columnNames = {"seller_id", "settlement_month"}),
                @UniqueConstraint(name = "uk_settlement_idempotency_key", columnNames = "idempotency_key")
        },
        indexes = @Index(name = "idx_settlement_status", columnList = "status, settlement_month"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseIdAndTime {

    private static final int REASON_MAX_LENGTH = 300;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false, columnDefinition = "CHAR(7)")
    private String settlementMonth;

    @Column(nullable = false)
    private long totalSalePrice;

    @Column(nullable = false)
    private long totalFee;

    @Column(nullable = false)
    private long payoutAmount;

    @Column(nullable = false)
    private int itemCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    /**
     * SETTLEMENT-{id}. IDENTITY라 INSERT 전에는 id를 모르므로 컬럼은 nullable로 두고,
     * INSERT 직후 같은 트랜잭션에서 {@link #assignIdempotencyKey()}로 채운다. 커밋된 행에는 항상 값이 있다.
     */
    @Column(length = 100)
    private String idempotencyKey;

    /** 실패한 시도 횟수. 자동 지급은 MAX_ATTEMPTS에 닿으면(3회) MANUAL_REQUIRED가 된다 */
    @Column(nullable = false)
    private int retryCount;

    private LocalDateTime nextRetryAt;

    @Column(length = 300)
    private String lastFailReason;

    @Column(length = 300)
    private String holdReason;

    private LocalDateTime processingStartedAt;

    private LocalDateTime paidAt;

    /** 지급 원장(wallet_transaction) ID. 결제 컨텍스트 테이블이라 연관관계 없이 ID만 둔다 */
    private Long walletTxId;

    private Settlement(Long sellerId, String settlementMonth, String holdReason) {
        this.sellerId = sellerId;
        this.settlementMonth = settlementMonth;
        this.status = holdReason == null ? SettlementStatus.READY : SettlementStatus.HOLD;
        this.holdReason = truncate(holdReason);
    }

    /**
     * 빈 정산서를 만든다. holdReason이 있으면(제재로 철회된 판매자) 처음부터 HOLD로 만든다.
     */
    public static Settlement create(Long sellerId, String settlementMonth, String holdReason) {
        if (sellerId == null || settlementMonth == null) {
            throw new IllegalArgumentException("정산서 필수 값이 비어 있습니다.");
        }
        return new Settlement(sellerId, settlementMonth, holdReason);
    }

    /**
     * 저장(INSERT)으로 id가 생긴 뒤 같은 트랜잭션에서 호출한다. 이미 있으면 바꾸지 않는다(불변).
     */
    public void assignIdempotencyKey() {
        if (getId() == null) throw new IllegalStateException("저장 전 정산서에는 멱등 키를 정할 수 없습니다.");
        if (idempotencyKey == null) this.idempotencyKey = SettlementPolicy.idempotencyKeyOf(getId());
    }

    /** 지급 전(READY·HOLD)인 정산서만 후보를 더 담을 수 있다. 그 외 상태면 후보는 다음 정산서로 이월된다 */
    public boolean canAcceptCandidates() {
        return status == SettlementStatus.READY || status == SettlementStatus.HOLD;
    }

    /** 후보를 담고 합계를 함께 올린다. 합계와 항목이 어긋나지 않도록 엔티티가 책임진다 */
    public void addCandidate(SettlementCandidate candidate) {
        if (!canAcceptCandidates()) throw stateInvalid();
        candidate.include(this);
        this.totalSalePrice += candidate.getSalePrice();
        this.totalFee += candidate.getFee();
        this.payoutAmount += candidate.getPayoutAmount();
        this.itemCount += 1;
    }

    public boolean isProcessing() {
        return status == SettlementStatus.PROCESSING;
    }

    /** 결제 컨텍스트에 남은 송금 기록의 금액이 이 정산서와 같은지 (복구 판단용) */
    public boolean matchesTransfer(long transferredPayoutAmount, long transferredFeeAmount) {
        return payoutAmount == transferredPayoutAmount && totalFee == transferredFeeAmount;
    }

    /** ③ 지급 성공 기록 */
    public void markPaid(Long walletTxId, LocalDateTime paidAt) {
        requireStatus(SettlementStatus.PROCESSING);
        this.status = SettlementStatus.PAID;
        this.walletTxId = walletTxId;
        this.paidAt = paidAt;
        this.nextRetryAt = null;
    }

    /**
     * ③ 자동 지급 실패 기록. 실패 횟수가 maxAttempts에 닿으면 MANUAL_REQUIRED, 아니면 FAILED + 다음 재시도 시각.
     */
    public void markFailed(String reason, LocalDateTime now, int maxAttempts, int retryIntervalMinutes) {
        requireStatus(SettlementStatus.PROCESSING);
        this.retryCount += 1;
        this.lastFailReason = truncate(reason);
        if (retryCount >= maxAttempts) {
            this.status = SettlementStatus.MANUAL_REQUIRED;
            this.nextRetryAt = null;
        } else {
            this.status = SettlementStatus.FAILED;
            this.nextRetryAt = now.plusMinutes(retryIntervalMinutes);
        }
    }

    /**
     * ③ 재시도해도 소용없는 실패(송금 기록 금액 불일치 등) 또는 관리자 수동 재처리 실패. 바로 MANUAL_REQUIRED.
     */
    public void markManualRequired(String reason) {
        requireStatus(SettlementStatus.PROCESSING);
        this.retryCount += 1;
        this.lastFailReason = truncate(reason);
        this.status = SettlementStatus.MANUAL_REQUIRED;
        this.nextRetryAt = null;
    }

    /** 관리자 보류·제재. 지급 전(READY·FAILED·MANUAL_REQUIRED)만 가능, 송금 중(PROCESSING)은 그대로 둔다 */
    public void hold(String reason) {
        requireStatus(SettlementStatus.READY, SettlementStatus.FAILED, SettlementStatus.MANUAL_REQUIRED);
        this.status = SettlementStatus.HOLD;
        this.holdReason = truncate(reason);
        this.nextRetryAt = null;
    }

    /** 보류 해제 → READY. 10분 스케줄러가 판매자 상태를 다시 보지 않고 자동 지급한다 */
    public void release() {
        requireStatus(SettlementStatus.HOLD);
        this.status = SettlementStatus.READY;
        this.holdReason = null;
        this.nextRetryAt = null;
    }

    private void requireStatus(SettlementStatus... allowed) {
        if (Arrays.stream(allowed).noneMatch(s -> s == status)) throw stateInvalid();
    }

    private static DomainException stateInvalid() {
        return new DomainException(SettlementErrorCode.SETTLEMENT_STATE_INVALID);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= REASON_MAX_LENGTH) return reason;
        return reason.substring(0, REASON_MAX_LENGTH);
    }
}
