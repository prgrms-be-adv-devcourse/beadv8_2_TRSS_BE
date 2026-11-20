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
 * 월 정산 Job = 확정 Step 하나
 * (구매 확정 이벤트로 후보를 만드는 수집, 지급, 사전 판별, 원장 재대조는 모두 Job 밖이다)
 *   확정: 판매자마다 READY 후보를 잠그고, 그 후보로 판별 규칙(금액, 수수료율, 원장 불일치, 판매자 제재)을 실행한 뒤
 *         PASS면 READY, HOLD면 HOLD 정산서를 만들고 지급 예정일을 정한다. BLOCK이면 확정하지 않는다
 *   지급: 지급 스케줄러(10분마다)가 지급 예정일이 된 READY와 재시도 시각이 된 FAILED를 지급한다
 *   사전 판별: 매월 1~9일 새벽 스케줄러가 같은 규칙을 미리 실행해 이력만 남긴다(관리자 미리 확인용)
 * Job 이름은 그대로 둔다. 실행 기록 조회, 수동 실행 가능한 달 계산이 이 이름으로 이전 실행을 찾기 때문이다
 * (이전 3단계 실행이 FAILED로 남은 달을 다시 실행하면, 확정 Step이 끝나지 않았던 경우 확정 Step부터 이어서 실행된다)
 * 재시작하면 확정 Step은 판매자 ID 커서 다음부터 이어서 처리한다.
 *
 * Tasklet에 트랜잭션 매니저를 넘기지 않는다(Spring Batch 6 기본값 ResourcelessTransactionManager)
 * 대신 Facade·UseCase가 판매자 1명 단위로 자기 트랜잭션을 열어, 한 건의 실패가 다른 건을 롤백시키지 않게 한다
 * 실패 건이 있으면 SettlementStepFailureListener가 Step 종료 코드를 "COMPLETED WITH FAILURES"로 바꾼다.
 */
@Configuration
@RequiredArgsConstructor
public class SettlementMonthlyJobConfig {

    private final JobRepository jobRepository;
    private final SettlementAggregateTasklet settlementAggregateTasklet;
    private final SettlementStepFailureListener settlementStepFailureListener;

    @Bean(name = SettlementJobNames.JOB_NAME)
    public Job settlementMonthlyJob() {
        return new JobBuilder(SettlementJobNames.JOB_NAME, jobRepository)
                .start(settlementAggregateStep())
                .build();
    }

    @Bean(name = SettlementJobNames.AGGREGATE_STEP)
    public Step settlementAggregateStep() {
        return new StepBuilder(SettlementJobNames.AGGREGATE_STEP, jobRepository)
                .tasklet(settlementAggregateTasklet)
                .listener(settlementStepFailureListener)
                .build();
    }
}
