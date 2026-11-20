package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.global.exception.DomainException;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.time.YearMonth;

/**
 * 두 Tasklet이 함께 쓰는 Job 파라미터 읽기·건수 누적·예외 요약 도우미
 */
final class SettlementBatchSupport {

    private SettlementBatchSupport() {
    }

    /** Job 파라미터 targetMonth(yyyy-MM). 없으면 Job 실행 방식이 잘못된 것이므로 Step을 실패시킨다 */
    static YearMonth targetMonth(StepContribution contribution) {
        String value = contribution.getStepExecution().getJobParameters()
                .getString(SettlementJobNames.PARAM_TARGET_MONTH);
        if (value == null) {
            throw new IllegalStateException("Job 파라미터 " + SettlementJobNames.PARAM_TARGET_MONTH + "가 없습니다.");
        }
        return YearMonth.parse(value);
    }

    /** Job 파라미터 trigger(실행 주체). 없으면(이 파라미터를 넣기 전에 시작한 실행의 재시작) 월 정산 Job으로 본다 */
    static SettlementTrigger trigger(StepContribution contribution) {
        String value = contribution.getStepExecution().getJobParameters()
                .getString(SettlementJobNames.PARAM_TRIGGER);
        return value == null ? SettlementTrigger.MONTHLY_JOB : SettlementTrigger.valueOf(value);
    }

    static Long jobExecutionId(StepContribution contribution) {
        return contribution.getStepExecution().getJobExecutionId();
    }

    /** Step 실행 컨텍스트의 건수를 누적한다. 커밋될 때 BATCH_STEP_EXECUTION_CONTEXT에 저장된다 */
    static void add(ExecutionContext context, String key, long amount) {
        context.putLong(key, context.getLong(key, 0L) + amount);
    }

    /** 로그 규칙(exception_handling 9장): 업무 예외 4xx는 INFO·스택 없음, 그 밖은 ERROR·스택 포함인지 */
    static boolean isClientError(Exception e) {
        return e instanceof DomainException de && de.statusCode() < 500;
    }

    static String errorCodeOf(Exception e) {
        return e instanceof DomainException de ? de.getResultCode() : e.getClass().getSimpleName();
    }

    static String reasonOf(Exception e) {
        return e instanceof DomainException de ? de.getMsg() : "처리 중 오류(" + e.getClass().getSimpleName() + ")";
    }
}