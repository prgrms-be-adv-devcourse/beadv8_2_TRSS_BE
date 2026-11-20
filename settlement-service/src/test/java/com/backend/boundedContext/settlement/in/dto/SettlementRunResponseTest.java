package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 월 정산 실행 결과: 지급 건수·금액은 지급 Step이 있던 이전 실행에만 있고, 지금 실행(확정 Step만)은 null
 */
class SettlementRunResponseTest {

    @Test
    @DisplayName("확정 Step만 있는 실행은 지급 건수·금액이 0이 아니라 null이다(지급은 지급 스케줄러가 한다)")
    void currentRun_payoutFieldsNull() {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        StepExecution aggregate = step(execution, SettlementJobNames.AGGREGATE_STEP, 1L);
        aggregate.getExecutionContext().putLong(SettlementJobNames.AGGREGATED_SELLER_COUNT, 3L);

        SettlementRunResponse response = SettlementRunResponse.from(execution, aggregate);

        assertEquals(3L, response.aggregatedSellerCount());
        assertNull(response.paidCount());
        assertNull(response.failedCount());
        assertNull(response.manualRequiredCount());
        assertNull(response.skippedCount());
        assertNull(response.payoutTotal());
        assertNull(response.feeTotal());
    }

    @Test
    @DisplayName("지급 Step이 있던 이전 실행은 그 Step이 남긴 지급 건수·금액을 그대로 보여 준다")
    void legacyRunWithPayoutStep_showsPayoutValues() {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        step(execution, SettlementJobNames.AGGREGATE_STEP, 1L);
        StepExecution payout = step(execution, SettlementJobNames.PAYOUT_STEP, 2L);
        payout.getExecutionContext().putLong(SettlementJobNames.PAID_COUNT, 2L);
        payout.getExecutionContext().putLong(SettlementJobNames.PAYOUT_TOTAL, 27_000L);

        SettlementRunResponse response = SettlementRunResponse.from(execution, null);

        assertEquals(2L, response.paidCount());
        assertEquals(27_000L, response.payoutTotal());
        assertEquals(0L, response.failedCount());   // 그 Step이 남기지 않은 값은 0
    }

    /** Job 실행에 Step 실행을 붙인다(Spring Batch 6은 StepExecution을 만들기만 해서는 Job 실행에 붙지 않는다) */
    private static StepExecution step(JobExecution execution, String stepName, Long id) {
        StepExecution step = MetaDataInstanceFactory.createStepExecution(execution, stepName, id);
        execution.addStepExecution(step);
        return step;
    }
}
