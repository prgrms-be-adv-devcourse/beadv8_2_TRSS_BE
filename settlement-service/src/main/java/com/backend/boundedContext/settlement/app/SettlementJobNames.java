package com.backend.boundedContext.settlement.app;

/**
 * 월 정산 Job의 이름·파라미터·실행 기록 키
 *
 * Job 정의(in/batch), 실행·조회 UseCase(app), 응답 변환(in/dto)이 같은 문자열을 쓰도록 한곳에 모은다
 */
public final class SettlementJobNames {

    public static final String JOB_NAME = "settlementMonthlyJob";
    /** 확정 Step (Job의 유일한 Step). 판별도 이 안에서 한다. Step 이름은 실행 기록 조회 호환을 위해 그대로 둔다 */
    public static final String AGGREGATE_STEP = "settlementAggregateStep";
    /** 예전 3단계 Job의 판별 Step 이름. 지금 Job에는 없고, 이전 실행 기록을 읽을 때만 쓴다 */
    public static final String JUDGE_STEP = "settlementJudgeStep";
    /** 예전 3단계 Job의 지급 Step 이름. 지금은 지급 스케줄러가 지급하고, 이전 실행 기록을 읽을 때만 쓴다 */
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
    /** 지급 요청 결과 모름(PROCESSING)으로 남은 정산서 수. 재확인 스케줄러가 확정한다 */
    public static final String PROCESSING_COUNT = "processingCount";
    public static final String MANUAL_REQUIRED_COUNT = "manualRequiredCount";
    public static final String SKIPPED_COUNT = "skippedCount";
    public static final String PAYOUT_TOTAL = "payoutTotal";
    public static final String FEE_TOTAL = "feeTotal";

    /** 확정 Step 안 판별 건수 */
    public static final String JUDGED_SELLER_COUNT = "judgedSellerCount";
    public static final String JUDGE_HOLD_COUNT = "judgeHoldCount";
    public static final String JUDGE_BLOCK_COUNT = "judgeBlockCount";
    public static final String JUDGE_FAILED_COUNT = "judgeFailedCount";
    private SettlementJobNames() {
    }
}