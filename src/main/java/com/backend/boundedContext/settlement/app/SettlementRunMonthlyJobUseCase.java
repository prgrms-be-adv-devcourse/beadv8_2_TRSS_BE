package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import com.backend.global.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 월 정산 Job(집계 Step → 지급 Step)을 실행한다. 매월 10일 스케줄러와 관리자 수동 실행이 함께 쓴다.
 *   정산월만 Job 파라미터로 쓴다. 같은 달이 실행 중이면 409 SETTLEMENT_RUNNING, 이미 완료된 달이면 409 SETTLEMENT_ALREADY_COMPLETED
 *   실패로 끝난 달은 같은 파라미터로 다시 실행하면 실패한 Step부터 이어서 실행된다(Spring Batch 재시작)
 *
 * Job은 동기로 실행되므로 이 메서드가 끝나면 결과(성공·실패 건수, 지급·수수료 총액)를 바로 돌려준다.
 */
@Slf4j
@Component
public class SettlementRunMonthlyJobUseCase {

    private final JobOperator jobOperator;
    private final Job settlementMonthlyJob;
    private final SettlementGetRunsUseCase settlementGetRunsUseCase;

    /** Job 빈은 Tasklet → Facade → 이 UseCase를 거쳐 다시 자신을 참조하므로 @Lazy로 순환을 끊는다 */
    public SettlementRunMonthlyJobUseCase(JobOperator jobOperator,
                                          @Lazy @Qualifier(SettlementJobNames.JOB_NAME) Job settlementMonthlyJob,
                                          SettlementGetRunsUseCase settlementGetRunsUseCase) {
        this.jobOperator = jobOperator;
        this.settlementMonthlyJob = settlementMonthlyJob;
        this.settlementGetRunsUseCase = settlementGetRunsUseCase;
    }

    /** @param trigger 실행 주체(MONTHLY_JOB: 스케줄러, ADMIN: 관리자)
     *                  같은 달 판별에는 쓰지 않는다 */
    public SettlementRunResponse run(YearMonth targetMonth, SettlementTrigger trigger) {
        if (!SettlementPolicy.isClosedMonth(targetMonth, LocalDateTime.now())) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_MONTH_INVALID);
        }
        JobParameters parameters = new JobParametersBuilder()
                .addString(SettlementJobNames.PARAM_TARGET_MONTH, targetMonth.toString())
                .addString(SettlementJobNames.PARAM_TRIGGER, trigger.name(), false)
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(settlementMonthlyJob, parameters);
            log.info("월 정산 Job 종료 targetMonth={} executionId={} status={}",
                    targetMonth, execution.getId(), execution.getStatus());
            return settlementGetRunsUseCase.toResponse(execution);
        } catch (JobExecutionAlreadyRunningException e) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_RUNNING, e);
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_ALREADY_COMPLETED, e);
        } catch (Exception e) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_JOB_FAILED, e);
        }
    }
}