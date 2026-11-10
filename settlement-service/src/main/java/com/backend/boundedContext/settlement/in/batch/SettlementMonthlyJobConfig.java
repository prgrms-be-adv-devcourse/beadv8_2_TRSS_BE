package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 월 정산 Job = 판별 Step → 확정(집계) Step → 지급 Step
 * (구매 확정 이벤트로 후보를 만드는 수집은 Job 밖의 상시 컨슈머다)
 *   판별: 지급 직전 자격 판정(후보 금액 스냅샷·원장 불일치·판매자 상태) → Job 실행 컨텍스트로 올림
 *   확정: 판정대로 정산서를 만든다(BLOCK 제외, HOLD는 HOLD 정산서)
 *   지급: READY 정산서 지급
 * 원장 재대조(원장 무결성 2단계 ②)는 Job 밖의 매일 03:35 스케줄러(SettlementScheduler.recheckLedgers)가 한다.
 * 앞 단계가 끝나야 다음 단계의 입력이 정해지므로 .start().next() 순서로 둔다.
 * 재시작하면 COMPLETED Step은 건너뛰고, 판정은 Job 실행 컨텍스트에 남아 있어 확정 Step이 그대로 읽는다.
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
    private final SettlementJudgeTasklet settlementJudgeTasklet;
    private final SettlementAggregateTasklet settlementAggregateTasklet;
    private final SettlementPayoutTasklet settlementPayoutTasklet;
    private final SettlementStepFailureListener settlementStepFailureListener;

    @Bean(name = SettlementJobNames.JOB_NAME)
    public Job settlementMonthlyJob() {
        return new JobBuilder(SettlementJobNames.JOB_NAME, jobRepository)
                .start(settlementJudgeStep())
                .next(settlementAggregateStep())
                .next(settlementPayoutStep())
                .build();
    }

    @Bean(name = SettlementJobNames.JUDGE_STEP)
    public Step settlementJudgeStep() {
        return new StepBuilder(SettlementJobNames.JUDGE_STEP, jobRepository)
                .tasklet(settlementJudgeTasklet)
                .listener(settlementStepFailureListener)
                .listener(judgementPromotionListener())
                .build();
    }

    /** 판별 Step의 판정을 Job 실행 컨텍스트로 올린다(실패 건이 있어 종료 코드가 바뀐 경우도 올림) */
    @Bean
    public ExecutionContextPromotionListener judgementPromotionListener() {
        ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[]{SettlementJobNames.JUDGEMENTS});
        listener.setStatuses(new String[]{ExitStatus.COMPLETED.getExitCode(),
                SettlementStepFailureListener.COMPLETED_WITH_FAILURES});
        return listener;
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