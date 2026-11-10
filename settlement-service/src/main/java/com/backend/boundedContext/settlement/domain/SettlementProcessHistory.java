package com.backend.boundedContext.settlement.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 정산 처리 이력
 *
 * 정산 후보·정산서의 모든 처리 결과(성공·실패·건너뜀)를 한 테이블에 남긴다
 * 저장만 하고 고치지 않는다
 * 성공 이력은 처리 트랜잭션 안에서, 실패 이력은 실패 기록 트랜잭션 안에서 저장해서 정산서 상태와 이력이 어긋나지 않게 한다
 * 실패 사유에는 스택트레이스·개인정보를 넣지 않는다(상세는 애플리케이션 로그)
 */
@Entity
@Immutable   // 원장 무결성 1단계: 저장(INSERT)만 하고 UPDATE 문을 만들지 않는다
@Table(name = "settlement_process_history",
        indexes = @Index(name = "idx_history_target", columnList = "target_type, target_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementProcessHistory extends BaseIdAndTime {

    private static final int TEXT_MAX_LENGTH = 300;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementHistoryTargetType targetType;

    /** 정산 후보 ID 또는 정산서 ID
     *
     * 대상이 만들어지지 못한 실패(후보 생성·집계 실패)는 null이고 사유에 식별값을 적는다 */
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SettlementHistoryStage stage;

    /** 시도 번호
     *
     * 지급은 1부터 늘고, 지급이 아닌 단계는 1 */
    @Column(nullable = false)
    private int attemptNo;

    /** 실행 경로
     *
     * trigger는 SQL 키워드라 컬럼 이름을 trigger_type으로 둔다 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementHistoryResult result;

    /** 실패했을 때의 resultCode
     * (예: 409-SETTLEMENT_AMOUNT_MISMATCH) */
    @Column(length = 100)
    private String errorCode;

    @Column(length = 300)
    private String failReason;

    /** 관리자 수동 재처리 사유 (ADMIN 경로만) */
    @Column(length = 300)
    private String adminReason;

    /** 월 정산 Job의 BATCH_JOB_EXECUTION ID (MONTHLY_JOB 경로만) */
    private Long batchExecutionId;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    private SettlementProcessHistory(SettlementHistoryTargetType targetType, Long targetId,
                                     SettlementHistoryStage stage, int attemptNo, SettlementTrigger triggerType,
                                     SettlementHistoryResult result, String errorCode, String failReason,
                                     String adminReason, Long batchExecutionId, LocalDateTime processedAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.stage = stage;
        this.attemptNo = attemptNo;
        this.triggerType = triggerType;
        this.result = result;
        this.errorCode = errorCode;
        this.failReason = truncate(failReason);
        this.adminReason = truncate(adminReason);
        this.batchExecutionId = batchExecutionId;
        this.processedAt = processedAt;
    }

    // ---------- 정산 후보 ----------

    public static SettlementProcessHistory candidateCreated(Long candidateId, LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.CANDIDATE, candidateId,
                SettlementHistoryStage.CANDIDATE_CREATE, 1, SettlementTrigger.EVENT,
                SettlementHistoryResult.SUCCEEDED, null, null, null, null, now);
    }

    /** 금액 이상으로 정산 후보 생성 실패
     * 후보 ID가 없으므로 사유에 주문 항목 ID를 적는다 */
    public static SettlementProcessHistory candidateRejected(String errorCode, String reason, LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.CANDIDATE, null,
                SettlementHistoryStage.CANDIDATE_CREATE, 1, SettlementTrigger.EVENT,
                SettlementHistoryResult.FAILED, errorCode, reason, null, null, now);
    }

    // ---------- 정산서 집계 ----------

    /** @param trigger 월 정산 Job을 실행한 주체(MONTHLY_JOB: 스케줄러, ADMIN: 관리자 수동 실행) */
    public static SettlementProcessHistory aggregated(Long settlementId, SettlementTrigger trigger,
                                                      SettlementHistoryResult result, String reason,
                                                      Long batchExecutionId, LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.SETTLEMENT, settlementId,
                SettlementHistoryStage.AGGREGATE, 1, trigger,
                result, null, reason, null, batchExecutionId, now);
    }

    /** 판매자 집계 실패
     * 정산서가 만들어지지 못했으므로 사유에 판매자 ID를 적는다 */
    public static SettlementProcessHistory aggregateFailed(SettlementTrigger trigger, String errorCode, String reason,
                                                           Long batchExecutionId, LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.SETTLEMENT, null,
                SettlementHistoryStage.AGGREGATE, 1, trigger,
                SettlementHistoryResult.FAILED, errorCode, reason, null, batchExecutionId, now);
    }

    // ---------- 지급 ----------

    public static SettlementProcessHistory payout(Long settlementId, int attemptNo, SettlementTrigger trigger,
                                                  SettlementHistoryResult result, String errorCode, String failReason,
                                                  String adminReason, Long batchExecutionId, LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.SETTLEMENT, settlementId,
                SettlementHistoryStage.PAYOUT, attemptNo, trigger, result, errorCode, failReason, adminReason,
                batchExecutionId, now);
    }

    // ---------- 원장 재대조 (단계 값은 VERIFY) ----------

    /**
     * 관리자가 원장 재대조 불일치를 확인하고 지급을 막지 않기로 한 기록 (stage VERIFY, trigger ADMIN, SUCCEEDED)
     * 정산서별 마지막 VERIFY 이력이 SUCCEEDED가 되므로 "풀리지 않은 불일치"에서 빠진다.
     * error_code에는 확인한 불일치 코드를 그대로 남긴다. 다음 재대조에서 같은 불일치가 나오면 직전 결과와 같아 이력을 다시 남기지 않으므로
     * 확인한 상태가 유지되고, 다른 불일치가 나오면 새 MANUAL_REQUIRED로 다시 막는다.
     * @param reason 관리자 확인 사유 (admin_reason)
     */
    public static SettlementProcessHistory ledgerMismatchConfirmed(Long settlementId, String mismatchErrorCode,
                                                                   String reason, LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.SETTLEMENT, settlementId,
                SettlementHistoryStage.VERIFY, 1, SettlementTrigger.ADMIN, SettlementHistoryResult.SUCCEEDED,
                mismatchErrorCode, null, reason, null, now);
    }

    /**
     * 지급된 정산서의 원장 재대조 결과
     * 같음 → SUCCEEDED, 다름 → MANUAL_REQUIRED(error_code에 LEDGER_* resultCode)
     * 정산서별 가장 마지막 VERIFY 이력이 MANUAL_REQUIRED면 "풀리지 않은 불일치"로 보고 판별 단계가 그 판매자 지급을 보류한다
     */
    public static SettlementProcessHistory ledgerChecked(Long settlementId, SettlementLedgerCheckResult checkResult,
                                                         SettlementTrigger trigger, Long batchExecutionId,
                                                         LocalDateTime now) {
        SettlementHistoryResult result = checkResult.isMismatch()
                ? SettlementHistoryResult.MANUAL_REQUIRED : SettlementHistoryResult.SUCCEEDED;
        return new SettlementProcessHistory(SettlementHistoryTargetType.SETTLEMENT, settlementId,
                SettlementHistoryStage.VERIFY, 1, trigger, result, checkResult.resultCode(), checkResult.message(),
                null, batchExecutionId, now);
    }

    // ---------- 판별 ----------

    /**
     * 판매자 1명의 판별 결과 (target_type = SELLER, target_id = 판매자 ID)
     * PASS → SUCCEEDED, HOLD → SKIPPED(이번 실행에서 지급 보류), BLOCK → MANUAL_REQUIRED(사람 확인)
     */
    public static SettlementProcessHistory sellerJudged(Long sellerId, SettlementJudgement judgement,
                                                        SettlementTrigger trigger, Long batchExecutionId,
                                                        LocalDateTime now) {
        SettlementHistoryResult result = switch (judgement.type()) {
            case PASS -> SettlementHistoryResult.SUCCEEDED;
            case HOLD -> SettlementHistoryResult.SKIPPED;
            case BLOCK -> SettlementHistoryResult.MANUAL_REQUIRED;
        };
        return new SettlementProcessHistory(SettlementHistoryTargetType.SELLER, sellerId,
                SettlementHistoryStage.JUDGE, 1, trigger, result, judgement.errorCode(), judgement.reason(), null,
                batchExecutionId, now);
    }

    /** 판별에서 금액 스냅샷이 맞지 않아 확정하지 않은 후보 1건 */
    public static SettlementProcessHistory candidateBlocked(Long candidateId, String errorCode, String reason,
                                                            SettlementTrigger trigger, Long batchExecutionId,
                                                            LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.CANDIDATE, candidateId,
                SettlementHistoryStage.JUDGE, 1, trigger, SettlementHistoryResult.MANUAL_REQUIRED, errorCode, reason,
                null, batchExecutionId, now);
    }

    // ---------- 보류·해제·제외 (관리자) ----------

    /**
     * 관리자가 정산서를 보류(HOLD)
     * @param reason 보류 사유 (admin_reason)
     */
    public static SettlementProcessHistory held(Long settlementId, String reason, LocalDateTime now) {
        return held(settlementId, reason, SettlementTrigger.ADMIN, now);
    }

    /**
     * 정산서 보류(HOLD). 보류를 시작한 경로를 함께 남긴다
     * - ADMIN: 관리자가 정산서 1건을 보류, 또는 회원 컨텍스트가 SettlementApi.holdSeller로 판매자 제재 보류(#225 전까지)
     * - EVENT: 판매자 제재 이벤트(SellerSanctionedEvent)를 받아 보류
     * - MONTHLY_JOB·RETRY_SCHEDULER·ADMIN(재처리): 지급 직전 판매자가 제재 상태여서 지급하지 않고 보류
     * @param reason 보류 사유 (admin_reason)
     */
    public static SettlementProcessHistory held(Long settlementId, String reason, SettlementTrigger trigger,
                                                LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.SETTLEMENT, settlementId,
                SettlementHistoryStage.HOLD, 1, trigger,
                SettlementHistoryResult.SUCCEEDED, null, null, reason, null, now);
    }

    /** 정산서 보류 해제(HOLD → READY). 이후 재시도 스케줄러가 지급한다 */
    public static SettlementProcessHistory released(Long settlementId, LocalDateTime now) {
        return released(settlementId, SettlementTrigger.ADMIN, null, now);
    }

    /**
     * 정산서 보류 해제. 해제한 경로를 함께 남긴다
     * - ADMIN: 관리자가 정산서 1건을 해제
     * - LEDGER_RECHECK: 원장 재대조 불일치가 풀려 그 사유로 보류한 정산서를 자동 해제
     * @param reason 해제 사유 (admin_reason, 없으면 null)
     */
    public static SettlementProcessHistory released(Long settlementId, SettlementTrigger trigger, String reason,
                                                    LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.SETTLEMENT, settlementId,
                SettlementHistoryStage.RELEASE, 1, trigger,
                SettlementHistoryResult.SUCCEEDED, null, null, reason, null, now);
    }

    /**
     * 정산 후보 제외(READY → EXCLUDED)
     * @param reason 제외 사유 (admin_reason)
     */
    public static SettlementProcessHistory candidateExcluded(Long candidateId, String reason, LocalDateTime now) {
        return new SettlementProcessHistory(SettlementHistoryTargetType.CANDIDATE, candidateId,
                SettlementHistoryStage.EXCLUDE, 1, SettlementTrigger.ADMIN,
                SettlementHistoryResult.SUCCEEDED, null, null, reason, null, now);
    }

    /** 원장 무결성 1단계: 처리 이력은 지우지 않는다 */
    @PreRemove
    private void denyRemove() {
        throw new IllegalStateException("정산 처리 이력은 삭제할 수 없습니다.");
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= TEXT_MAX_LENGTH) return text;
        return text.substring(0, TEXT_MAX_LENGTH);
    }
}