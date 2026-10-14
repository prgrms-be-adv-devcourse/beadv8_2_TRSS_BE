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
        long paidCount,
        long failedCount,
        long manualRequiredCount,
        long skippedCount,
        long payoutTotal,
        long feeTotal
) {
    /** @param aggregateStep 집계 Step 기록
     *                        한 번도 실행되지 않았으면 null(건수 0) */
    public static SettlementRunResponse from(JobExecution execution, StepExecution aggregateStep) {
        ExecutionContext aggregate = aggregateStep == null ? new ExecutionContext() : aggregateStep.getExecutionContext();
        ExecutionContext payout = new ExecutionContext();
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
                payout.getLong(SettlementJobNames.PAID_COUNT, 0L),
                payout.getLong(SettlementJobNames.FAILED_COUNT, 0L),
                payout.getLong(SettlementJobNames.MANUAL_REQUIRED_COUNT, 0L),
                payout.getLong(SettlementJobNames.SKIPPED_COUNT, 0L),
                payout.getLong(SettlementJobNames.PAYOUT_TOTAL, 0L),
                payout.getLong(SettlementJobNames.FEE_TOTAL, 0L));
    }
}