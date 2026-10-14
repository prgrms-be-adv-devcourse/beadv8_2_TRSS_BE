package com.backend.boundedContext.settlement.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    private static String truncate(String text) {
        if (text == null || text.length() <= TEXT_MAX_LENGTH) return text;
        return text.substring(0, TEXT_MAX_LENGTH);
    }
}