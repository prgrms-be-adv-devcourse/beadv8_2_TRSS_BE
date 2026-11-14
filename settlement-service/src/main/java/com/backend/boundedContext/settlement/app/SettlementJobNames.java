package com.backend.boundedContext.settlement.app;

/**
 * 월 정산 Job의 이름·파라미터·실행 기록 키
 *
 * Job 정의(in/batch), 실행·조회 UseCase(app), 응답 변환(in/dto)이 같은 문자열을 쓰도록 한곳에 모은다
 */
public final class SettlementJobNames {

    public static final String JOB_NAME = "settlementMonthlyJob";
    /** 1단계 판별: 지급 직전 자격 판정 */
    public static final String JUDGE_STEP = "settlementJudgeStep";
    /** 2단계 확정: 정산서 집계 (Step 이름은 실행 기록 조회 호환을 위해 그대로 둔다) */
    public static final String AGGREGATE_STEP = "settlementAggregateStep";
    public static final String PAYOUT_STEP = "settlementPayoutStep";

    /** 대상 정산월(yyyy-MM)
     *
     * 이 값만 Job 파라미터로 써서 같은 달은 한 번만 완료되게 한다 */
    public static final String PARAM_TARGET_MONTH = "targetMonth";

    /** 실행 주체(MONTHLY_JOB: 스케줄러, ADMIN: 관리자 수동 실행). 처리 이력의 trigger_type에 남긴다
     *
     * 같은 달 판별에 쓰이지 않도록 식별용이 아닌(non-identifying) 파라미터로 넘긴다 */
    public static final String PARAM_TRIGGER = "trigger";

    /** Step 실행 컨텍스트에 남기는 커서·건수 키 */
    public static final String CURSOR_SELLER_ID = "lastSellerId";
    public static final String CURSOR_SETTLEMENT_ID = "lastSettlementId";
    public static final String AGGREGATED_SELLER_COUNT = "aggregatedSellerCount";
    public static final String AGGREGATED_CANDIDATE_COUNT = "aggregatedCandidateCount";
    public static final String FAILED_SELLER_COUNT = "failedSellerCount";
    public static final String PAID_COUNT = "paidCount";
    public static final String FAILED_COUNT = "failedCount";
    public static final String MANUAL_REQUIRED_COUNT = "manualRequiredCount";
    public static final String SKIPPED_COUNT = "skippedCount";
    public static final String PAYOUT_TOTAL = "payoutTotal";
    public static final String FEE_TOTAL = "feeTotal";

    /** 판별 Step 건수와 판정 (판정은 Job 실행 컨텍스트로 올려 확정 Step이 읽는다) */
    public static final String JUDGED_SELLER_COUNT = "judgedSellerCount";
    public static final String JUDGE_HOLD_COUNT = "judgeHoldCount";
    public static final String JUDGE_BLOCK_COUNT = "judgeBlockCount";
    public static final String JUDGE_FAILED_COUNT = "judgeFailedCount";
    /** 판매자 ID → SettlementJudgement.encode(). HashMap이라 Job 실행 컨텍스트에 직렬화된다 */
    public static final String JUDGEMENTS = "judgements";
    /**
     * 판정을 만든 JobExecution ID. 판정과 함께 Job 실행 컨텍스트로 올린다.
     * 재시작하면 Spring Batch가 직전 실행의 Job 실행 컨텍스트를 새 실행에 넘기므로, 확정 Step은 이 값이 지금 실행과 같을 때만 판정을 쓴다
     */
    public static final String JUDGED_EXECUTION_ID = "judgedExecutionId";

    private SettlementJobNames() {
    }
}