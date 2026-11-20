package com.backend.boundedContext.settlement.app;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 강제 종료로 STARTED에 멈춘 월 정산 실행을 FAILED로 정리해 다시 실행할 수 있게 만든다
 *
 * 서버가 Job 실행 중에 꺼지면(배포·OOM·kill) Spring Batch 메타 테이블에 실행이 STARTED로 남는다.
 * 그대로 두면 같은 달을 다시 실행할 때 409 SETTLEMENT_RUNNING이 나고, 재시도·재대조 스케줄러도
 * "Job 실행 중"으로 보고 계속 건너뛴다. 그래서 앱이 뜰 때 이 실행들을 JobOperator.recover(Spring Batch 6)로 FAILED 처리한다.
 * 이후 같은 정산월로 다시 실행하면 끝난 Step은 건너뛰고 멈춘 Step의 커서부터 이어서 처리한다.
 *
 * 전제: settlement-service는 한 대로 운영한다(infra/compose.prod.yaml). 앱이 막 뜬 시점에는 이 서버에서 도는 Job이 없으므로
 * 남아 있는 STARTED 실행은 모두 이전 프로세스가 남긴 것이다. 여러 대로 늘리면 다른 서버가 실행 중인 Job을 건드리지 않도록
 * 마지막 갱신 시각 조건을 더해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementRecoverJobUseCase {

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;

    /** @return FAILED로 정리한 실행 수 */
    public int recoverInterruptedJobs() {
        Set<JobExecution> running = jobRepository.findRunningJobExecutions(SettlementJobNames.JOB_NAME);
        for (JobExecution execution : running) {
            jobOperator.recover(execution);
            log.warn("중단된 월 정산 실행을 FAILED로 정리함 executionId={} parameters={}",
                    execution.getId(), execution.getJobParameters());
        }
        return running.size();
    }
}
