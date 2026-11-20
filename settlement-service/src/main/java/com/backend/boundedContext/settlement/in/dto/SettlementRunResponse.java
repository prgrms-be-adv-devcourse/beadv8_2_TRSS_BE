package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.time.LocalDateTime;

/**
 * 월 정산 Job 실행 1건의 결과
 * Spring Batch 메타 테이블(BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION)에서 읽는다.
 * 건수·금액은 각 Step의 Tasklet이 Step 실행 컨텍스트에 남긴 값이다.
 * 집계 Step 기록은 호출한 쪽이 넘긴다(재시작한 실행에는 집계 Step이 없어 이전 실행의 기록을 쓰기 때문).
 * 지급 건수·금액(paidCount ~ feeTotal)은 Job에 지급 Step이 있던 이전 실행에만 있고, 지금 실행은 null이다.
 * 지금은 Job이 지급하지 않고 지급 스케줄러가 지급 예정일부터 지급하므로, 지급 결과는 정산서 목록·요약에서 본다.
 */
public record SettlementRunResponse(
        long jobExecutionId,
        String targetMonth,
        String status,
        String exitCode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        long aggregatedSellerCount,
        long aggregatedCandidateCount,
        long failedSellerCount,
        Long paidCount,
        Long failedCount,
        Long manualRequiredCount,
        Long skippedCount,
        Long payoutTotal,
        Long feeTotal
) {
    /** @param aggregateStep 집계 Step 기록
     *                        한 번도 실행되지 않았으면 null(건수 0) */
    public static SettlementRunResponse from(JobExecution execution, StepExecution aggregateStep) {
        ExecutionContext aggregate = aggregateStep == null ? new ExecutionContext() : aggregateStep.getExecutionContext();
        ExecutionContext payout = null;   // 지급 Step이 있던 이전 실행만
        for (StepExecution step : execution.getStepExecutions()) {
            if (SettlementJobNames.PAYOUT_STEP.equals(step.getStepName())) payout = step.getExecutionContext();
        }
        return new SettlementRunResponse(
                execution.getId(),
                execution.getJobParameters().getString(SettlementJobNames.PARAM_TARGET_MONTH),
                execution.getStatus().name(),
                execution.getExitStatus().getExitCode(),
                execution.getStartTime(),
                execution.getEndTime(),
                aggregate.getLong(SettlementJobNames.AGGREGATED_SELLER_COUNT, 0L),
                aggregate.getLong(SettlementJobNames.AGGREGATED_CANDIDATE_COUNT, 0L),
                aggregate.getLong(SettlementJobNames.FAILED_SELLER_COUNT, 0L),
                payoutValue(payout, SettlementJobNames.PAID_COUNT),
                payoutValue(payout, SettlementJobNames.FAILED_COUNT),
                payoutValue(payout, SettlementJobNames.MANUAL_REQUIRED_COUNT),
                payoutValue(payout, SettlementJobNames.SKIPPED_COUNT),
                payoutValue(payout, SettlementJobNames.PAYOUT_TOTAL),
                payoutValue(payout, SettlementJobNames.FEE_TOTAL));
    }

    /** 지급 Step이 없는 실행(지금 Job)은 null, 있던 이전 실행은 그 Step이 남긴 값(없으면 0) */
    private static Long payoutValue(ExecutionContext payout, String key) {
        return payout == null ? null : payout.getLong(key, 0L);
    }
}