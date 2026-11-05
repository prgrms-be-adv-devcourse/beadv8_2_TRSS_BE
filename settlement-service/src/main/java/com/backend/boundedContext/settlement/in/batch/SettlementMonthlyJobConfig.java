package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 월 정산 Job = 집계 Step → 지급 Step
 * 집계가 성공해야 지급할 정산서가 확정되므로 .start().next() 순서로 둔다.
 *
 * Tasklet에 트랜잭션 매니저를 넘기지 않는다(Spring Batch 6 기본값 ResourcelessTransactionManager)
 * 대신 Facade·UseCase가 판매자 1명·정산서 1건 단위로 자기 트랜잭션을 열어, 한 건의 실패가 다른 건을 롤백시키지 않게 한다
 * 실패 건이 있으면 SettlementStepFailureListener가 Step 종료 코드를 "COMPLETED WITH FAILURES"로 바꾼다.
 * 배치는 트리거 역할만 하고, 대상 선정·지급·실패 기록 규칙은 Facade·UseCase에 둔다.
 */
@Configuration
@RequiredArgsConstructor
public class SettlementMonthlyJobConfig {

    private final JobRepository jobRepository;
    private final SettlementAggregateTasklet settlementAggregateTasklet;
    private final SettlementPayoutTasklet settlementPayoutTasklet;
    private final SettlementStepFailureListener settlementStepFailureListener;

    @Bean(name = SettlementJobNames.JOB_NAME)
    public Job settlementMonthlyJob() {
        return new JobBuilder(SettlementJobNames.JOB_NAME, jobRepository)
                .start(settlementAggregateStep())
                .next(settlementPayoutStep())
                .build();
    }

    @Bean(name = SettlementJobNames.AGGREGATE_STEP)
    public Step settlementAggregateStep() {
        return new StepBuilder(SettlementJobNames.AGGREGATE_STEP, jobRepository)
                .tasklet(settlementAggregateTasklet)
                .listener(settlementStepFailureListener)
                .build();
    }

    @Bean(name = SettlementJobNames.PAYOUT_STEP)
    public Step settlementPayoutStep() {
        return new StepBuilder(SettlementJobNames.PAYOUT_STEP, jobRepository)
                .tasklet(settlementPayoutTasklet)
                .listener(settlementStepFailureListener)
                .build();
    }
}