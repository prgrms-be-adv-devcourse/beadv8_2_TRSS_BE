package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * 집계·지급 Step이 정상 종료했지만 실패 건이 있으면 Step 종료 코드를 "COMPLETED WITH FAILURES"로 바꾼다.
 * 배치 메타 테이블(BATCH_STEP_EXECUTION.EXIT_CODE)만 보는 사람도 "정상 종료지만 실패 건이 있다"는 걸 알 수 있다.
 * 실패 건수는 Tasklet이 Step 실행 컨텍스트에 남기고, 판매자·정산서별 실패 내용은 처리 이력(settlement_process_history)에서 본다.
 * Step의 BatchStatus는 COMPLETED 그대로라서 Job 재시작·같은 달 재실행 규칙에는 영향이 없다.
 */
@Component
public class SettlementStepFailureListener implements StepExecutionListener {

    public static final String COMPLETED_WITH_FAILURES = "COMPLETED WITH FAILURES";

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (!ExitStatus.COMPLETED.getExitCode().equals(stepExecution.getExitStatus().getExitCode())) {
            return null;   // 실패·중단은 Spring Batch가 정한 종료 코드를 그대로 둔다
        }
        long failures = failureCount(stepExecution.getExecutionContext());
        if (failures == 0) return null;
        return new ExitStatus(COMPLETED_WITH_FAILURES, "실패 " + failures + "건 (상세는 settlement_process_history)");
    }

    /** 집계 실패 판매자 + 지급 실패 + 지급 수동 필요 건수 */
    private static long failureCount(ExecutionContext context) {
        return context.getLong(SettlementJobNames.FAILED_SELLER_COUNT, 0L)
                + context.getLong(SettlementJobNames.FAILED_COUNT, 0L)
                + context.getLong(SettlementJobNames.MANUAL_REQUIRED_COUNT, 0L);
    }
}