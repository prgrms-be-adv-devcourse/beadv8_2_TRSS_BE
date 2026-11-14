package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.payment.dto.TransferResultDto;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * 정산서
 *
 * 판매자 1명 × 정산월 1개 = 1건이며, 지급 처리 단위(판매자 1명 = 정산서 1건 = 처리 단위 1개)
 * 지급은 ① 짧은 트랜잭션에서 PROCESSING 선점 → ② 트랜잭션 밖에서 결제 지급 요청 → ③ 짧은 트랜잭션에서 결과 기록으로 나뉜다.
 * 결제가 다른 서비스(HTTP)라 결과를 모르는 경우(응답 없음, 5xx)가 생기므로, 그동안은 PROCESSING으로 둔다
 * 상태는 엔티티 메서드로만 바꾸고, 잘못된 전이는 409 SETTLEMENT_STATE_INVALID로 막는다
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

    // 원장 무결성 1단계: 판매자·정산월은 UPDATE 문에서 빠진다. 합계는 집계(addCandidate)에서 늘어나므로 막지 않고
    // 지급 직전 matchesCandidateTotals(정산서 합계 = 묶인 후보 합계)로 확인한다
    @Column(nullable = false, updatable = false)
    private Long sellerId;

    @JdbcTypeCode(SqlTypes.CHAR)   // CHAR(7) 컬럼과 맞춤 (없으면 ddl-auto validate가 VARCHAR를 기대해 실패)
    @Column(nullable = false, updatable = false, columnDefinition = "CHAR(7)")
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
     * INSERT 직후 같은 트랜잭션에서 {@link #assignIdempotencyKey()}로 채운다
     * 커밋된 행에는 항상 값이 있다
     */
    @Column(length = 100)
    private String idempotencyKey;

    /** 실패한 시도 횟수
     * 자동 지급은 MAX_ATTEMPTS에 닿으면(3회) MANUAL_REQUIRED가 된다 */
    @Column(nullable = false)
    private int retryCount;

    private LocalDateTime nextRetryAt;

    @Column(length = 300)
    private String lastFailReason;

    @Column(length = 300)
    private String holdReason;

    private LocalDateTime paidAt;

    /** 지급 요청을 시작한 시각(PROCESSING으로 바꾼 시각). 재확인 스케줄러가 이 시각 + processingRecheckMinutes가 지난 건을 확정한다 */
    private LocalDateTime processingStartedAt;

    /** 지금 PROCESSING이 관리자 재처리(MANUAL_REQUIRED에서 시작)인지. 재확인에서 지급 기록이 없으면
     * 자동 재시도(FAILED)로 되돌리지 않고 MANUAL_REQUIRED로 둔다. PROCESSING이 끝나면 false */
    @Column(nullable = false)
    private boolean processingManual;

    /** 지급 원장(wallet_transaction) ID
     * 결제 컨텍스트 테이블이라 연관관계 없이 ID만 둔다 */
    private Long walletTxId;

    private Settlement(Long sellerId, String settlementMonth, String holdReason) {
        this.sellerId = sellerId;
        this.settlementMonth = settlementMonth;
        this.status = holdReason == null ? SettlementStatus.READY : SettlementStatus.HOLD;
        this.holdReason = truncate(holdReason);
    }

    /**
     * 빈 정산서를 만든다
     * holdReason이 있으면(제재로 철회된 판매자) 처음부터 HOLD로 만든다
     */
    public static Settlement create(Long sellerId, String settlementMonth, String holdReason) {
        if (sellerId == null || settlementMonth == null) {
            throw new IllegalArgumentException("정산서 필수 값이 비어 있습니다.");
        }
        return new Settlement(sellerId, settlementMonth, holdReason);
    }

    /**
     * 저장(INSERT)으로 id가 생긴 뒤 같은 트랜잭션에서 호출한다
     * 이미 있으면 바꾸지 않는다(불변)
     */
    public void assignIdempotencyKey() {
        if (getId() == null) throw new IllegalStateException("저장 전 정산서에는 멱등 키를 정할 수 없습니다.");
        if (idempotencyKey == null) this.idempotencyKey = SettlementPolicy.idempotencyKeyOf(getId());
    }

    /** 지급 전(READY·HOLD)인 정산서만 후보를 더 담을 수 있다
     * 그 외 상태면 후보는 다음 정산서로 이월된다 */
    public boolean canAcceptCandidates() {
        return status == SettlementStatus.READY || status == SettlementStatus.HOLD;
    }

    /** 후보를 담고 합계를 함께 올린다
     * 합계와 항목이 어긋나지 않도록 엔티티가 책임진다 */
    public void addCandidate(SettlementCandidate candidate) {
        if (!canAcceptCandidates()) throw stateInvalid();
        candidate.include(this);
        this.totalSalePrice += candidate.getSalePrice();
        this.totalFee += candidate.getFee();
        this.payoutAmount += candidate.getPayoutAmount();
        this.itemCount += 1;
    }

    /**
     * 이 경로로 지금 지급해도 되는지(지급 가능한 상태인지 확인)
     *
     * 자동 지급(월 정산 Job·재시도 스케줄러, 관리자가 수동 실행한 월 정산 Job 포함): READY, 또는 다음 재시도 일시가 지난 FAILED
     * 관리자 수동 재처리(manualRetry): MANUAL_REQUIRED만
     * PAID·HOLD는 어떤 경로로도 지급하지 않는다
     * 자동 경로가 MANUAL_REQUIRED를 지급하지 않는 것은 "자동 재시도를 멈춘다"는 규칙
     * TODO 파이널 프로젝트 때 자동화 변경 예정
     */
    public boolean isPayable(boolean manualRetry, LocalDateTime now) {
        if (manualRetry) return status == SettlementStatus.MANUAL_REQUIRED;
        if (status == SettlementStatus.READY) return true;
        return status == SettlementStatus.FAILED && nextRetryAt != null && !nextRetryAt.isAfter(now);
    }

    /** 이번 지급이 몇 번째 시도인지 (실패 횟수 + 1) */
    public int nextAttemptNo() {
        return retryCount + 1;
    }

    /** 지급 전 확인: 정산서 합계와 묶인 정산 후보의 합계가 같은지 */
    public boolean matchesCandidateTotals(long saleTotal, long feeTotal, long payoutTotal, long count) {
        return totalSalePrice == saleTotal && totalFee == feeTotal && payoutAmount == payoutTotal && itemCount == count;
    }

    /** 결제 컨텍스트가 돌려준(또는 이미 있던) 지급 내역의 금액이 이 정산서와 같은지 */
    public boolean matchesTransfer(long transferredPayoutAmount, long transferredFeeAmount) {
        return payoutAmount == transferredPayoutAmount && totalFee == transferredFeeAmount;
    }

    /**
     * 원장 무결성 2단계 ②: 지급 영수증(이 정산서의 원장 행 ID·지급액·수수료)과 결제 원장을 다시 읽은 값을 비교한다
     * 지급 당시 matchesTransfer로 금액이 같음을 확인하고 PAID가 됐으므로, 정산서 금액이 곧 지급 영수증이다
     * @param ledger 결제 findTransfer 결과. 지급 기록이 없으면 null
     */
    public SettlementLedgerCheckResult checkLedger(TransferResultDto ledger) {
        if (status != SettlementStatus.PAID) throw stateInvalid();
        if (ledger == null) return SettlementLedgerCheckResult.MISSING;
        if (walletTxId != null && !walletTxId.equals(ledger.walletTransactionId())) {
            return SettlementLedgerCheckResult.REPLACED;
        }
        if (!matchesTransfer(ledger.payoutAmount(), ledger.feeAmount())) {
            return SettlementLedgerCheckResult.AMOUNT_CHANGED;
        }
        return SettlementLedgerCheckResult.OK;
    }

    /** 원장 무결성 1단계: 정산서는 지우지 않는다(정정은 조정으로) */
    @PreRemove
    private void denyRemove() {
        throw new IllegalStateException("정산서는 삭제할 수 없습니다. 보류·조정으로 정정하세요.");
    }

    /**
     * ① 지급 요청 선점: 결제를 호출하기 전에 커밋한다
     * 지급 가능한 상태(READY·FAILED·MANUAL_REQUIRED)에서만 가능하다
     */
    public void markProcessing(LocalDateTime now) {
        markProcessing(now, false);
    }

    /** manual: 관리자 재처리로 시작한 지급 요청인지(재확인에서 지급 기록이 없을 때 MANUAL_REQUIRED로 되돌리기 위해 남긴다) */
    public void markProcessing(LocalDateTime now, boolean manual) {
        requirePayableStatus();
        this.status = SettlementStatus.PROCESSING;
        this.processingStartedAt = now;
        this.processingManual = manual;
        this.nextRetryAt = null;
    }

    /** PROCESSING이 된 지 recheckMinutes가 지났는지 (재확인 스케줄러 대상) */
    public boolean isProcessingOverdue(LocalDateTime now, int recheckMinutes) {
        return status == SettlementStatus.PROCESSING && processingStartedAt != null
                && !processingStartedAt.plusMinutes(recheckMinutes).isAfter(now);
    }

    /**
     * ③ 지급 성공
     * 지급 경로는 PROCESSING에서 부른다. 테스트·데이터 준비를 위해 지급 가능 상태에서도 허용한다
     */
    public void markPaid(Long walletTxId, LocalDateTime paidAt) {
        requireStatus(SettlementStatus.PROCESSING, SettlementStatus.READY, SettlementStatus.FAILED,
                SettlementStatus.MANUAL_REQUIRED);
        this.processingStartedAt = null;
        this.processingManual = false;
        this.status = SettlementStatus.PAID;
        this.walletTxId = walletTxId;
        this.paidAt = paidAt;
        this.nextRetryAt = null;
    }

    /**
     * 보류(HOLD) 중 지급 확인: 이 정산서 멱등 키의 지급 결과(결제 응답)로 이미 지급된 것을 확인했을 때만 쓴다
     * HOLD는 "지급하지 않는다"는 뜻이지만 돈이 이미 움직였으므로 상태를 사실에 맞춘다(남겨 두면 미정산 금액에 계속 잡힌다)
     * 보류 사유는 지우고, 호출한 쪽이 이력에 남긴다. 일반 지급 경로의 HOLD → PAID는 여전히 막는다({@link #markPaid})
     */
    public void markPaidWhileHeld(Long walletTxId, LocalDateTime paidAt) {
        requireStatus(SettlementStatus.HOLD);
        this.holdReason = null;
        this.processingStartedAt = null;
        this.processingManual = false;
        this.status = SettlementStatus.PAID;
        this.walletTxId = walletTxId;
        this.paidAt = paidAt;
        this.nextRetryAt = null;
    }

    /**
     * ② 자동 지급 실패 기록
     * 실패 횟수가 maxAttempts에 닿으면 MANUAL_REQUIRED, 아니면 FAILED + 다음 재시도 일시
     * 다음 재시도 일시는 실패 시각을 분 단위로 자른 뒤 간격을 더한다.
     * 재시도 스케줄러가 10분마다 정각(00:10:00)에 돌므로, 초를 남기면(00:10:05) 그 회차를 놓치고 다음 회차로 밀린다
     * (테스트 00:00 → 00:10 → 00:20, 운영 00:00 → 02:00 → 04:00)
     */
    public void markFailed(String reason, LocalDateTime now, int maxAttempts, int retryIntervalMinutes) {
        // MANUAL_REQUIRED는 자동 재시도로 되돌리지 않는다(3회 실패하면 자동 재시도를 멈춘다)
        // PROCESSING: 결제가 확실히 거절했거나(4xx) 재확인에서 지급 기록이 없을 때
        requireStatus(SettlementStatus.READY, SettlementStatus.FAILED, SettlementStatus.PROCESSING);
        this.processingStartedAt = null;
        this.processingManual = false;
        this.retryCount += 1;
        this.lastFailReason = truncate(reason);
        if (retryCount >= maxAttempts) {
            this.status = SettlementStatus.MANUAL_REQUIRED;
            this.nextRetryAt = null;
        } else {
            this.status = SettlementStatus.FAILED;
            this.nextRetryAt = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(retryIntervalMinutes);
        }
    }

    /**
     * ② 재시도해도 결과가 같은 실패(금액 불일치, 지갑 없음 등) 또는 관리자 수동 재처리 실패
     * 바로 MANUAL_REQUIRED
     */
    public void markManualRequired(String reason) {
        requireStatus(SettlementStatus.READY, SettlementStatus.FAILED, SettlementStatus.MANUAL_REQUIRED,
                SettlementStatus.PROCESSING);
        this.processingStartedAt = null;
        this.processingManual = false;
        this.retryCount += 1;
        this.lastFailReason = truncate(reason);
        this.status = SettlementStatus.MANUAL_REQUIRED;
        this.nextRetryAt = null;
    }

    /** 관리자 보류·제재
     * 지급 전(READY·FAILED·MANUAL_REQUIRED)만 가능 */
    public void hold(String reason) {
        requirePayableStatus();
        this.status = SettlementStatus.HOLD;
        this.holdReason = truncate(reason);
        this.nextRetryAt = null;
    }

    /** 보류 해제 → READY
     * 재시도 스케줄러가 지급한다. 지급 직전에 판매자가 제재 상태면 다시 HOLD가 된다(SettlementPayoutUseCase) */
    public void release() {
        requireStatus(SettlementStatus.HOLD);
        this.status = SettlementStatus.READY;
        this.holdReason = null;
        this.nextRetryAt = null;
    }

    /** 지급 가능한 상태(READY·FAILED·MANUAL_REQUIRED)인지
     * 경로별 조건은 {@link #isPayable} */
    public boolean isPayableStatus() {
        return status == SettlementStatus.READY || status == SettlementStatus.FAILED
                || status == SettlementStatus.MANUAL_REQUIRED;
    }

    private void requirePayableStatus() {
        requireStatus(SettlementStatus.READY, SettlementStatus.FAILED, SettlementStatus.MANUAL_REQUIRED);
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