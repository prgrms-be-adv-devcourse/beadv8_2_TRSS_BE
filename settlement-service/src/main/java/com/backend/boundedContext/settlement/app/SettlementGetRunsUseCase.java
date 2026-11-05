package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 월 정산 Job 실행 결과 조회
 *
 * 정산 업무 테이블이 아니라 Spring Batch 메타 테이블을 읽는다(최신 실행부터).
 * 같은 달을 재시작했으면 실행 기록이 여러 건 나온다.
 * 페이지는 실행(JobExecution) 단위로 나눈다. 정산월(JobInstance) 단위로 나누면 재시작한 달이 있을 때
 * 한 페이지 항목 수가 size보다 많아지고 totalElements와도 맞지 않기 때문이다.
 * 실행 기록은 한 달에 몇 건뿐이라 전체를 읽어 메모리에서 자른다.
 */
@Component
@RequiredArgsConstructor
public class SettlementGetRunsUseCase {

    private final JobRepository jobRepository;

    public PageResponse<SettlementRunResponse> getRuns(int page, int size) {
        List<JobInstance> instances =
                jobRepository.getJobInstances(SettlementJobNames.JOB_NAME, 0, (int) countInstances());
        List<JobExecution> executions = instances.stream()
                .flatMap(instance -> jobRepository.getJobExecutions(instance).stream())
                .sorted(Comparator.comparingLong(JobExecution::getId).reversed())
                .toList();
        List<SettlementRunResponse> items = executions.stream()
                .skip((long) page * size)
                .limit(size)
                .map(this::toResponse)
                .toList();
        long total = executions.size();
        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }

    /**
     * 실행 1건의 결과
     * 재시작한 실행은 이미 완료된 집계 Step을 다시 돌리지 않아 집계 Step 기록이 없으므로,
     * 같은 달(JobInstance)의 마지막 집계 Step 기록으로 집계 건수를 채운다
     */
    public SettlementRunResponse toResponse(JobExecution execution) {
        StepExecution aggregateStep = execution.getStepExecutions().stream()
                .filter(step -> SettlementJobNames.AGGREGATE_STEP.equals(step.getStepName()))
                .findFirst()
                .orElseGet(() -> jobRepository.getLastStepExecution(execution.getJobInstance(),
                        SettlementJobNames.AGGREGATE_STEP));
        return SettlementRunResponse.from(execution, aggregateStep);
    }

    /** 월 정산 Job이 지금 실행 중인지 (재시도 스케줄러가 Job과 겹치지 않게 확인) */
    public boolean isRunning() {
        return !jobRepository.findRunningJobExecutions(SettlementJobNames.JOB_NAME).isEmpty();
    }

    private long countInstances() {
        try {
            return jobRepository.getJobInstanceCount(SettlementJobNames.JOB_NAME);
        } catch (NoSuchJobException e) {
            return 0L;   // 아직 한 번도 실행하지 않음
        }
    }
}