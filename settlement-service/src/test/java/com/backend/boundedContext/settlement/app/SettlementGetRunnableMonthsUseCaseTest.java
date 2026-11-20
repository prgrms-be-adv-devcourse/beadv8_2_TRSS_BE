package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.in.dto.SettlementRunnableMonthResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수동 실행 확인 창의 "실행할 달": 실패·중단된 달과, 자동 실행 시각이 지났는데 실행되지 않은 지난달만
 */
class SettlementGetRunnableMonthsUseCaseTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final SettlementGetRunnableMonthsUseCase useCase =
            new SettlementGetRunnableMonthsUseCase(jobRepository, new SettlementPolicy(10, 10, 3, 10, 100, 50));
    private final List<JobInstance> instances = new ArrayList<>();
    private long nextExecutionId = 1;

    @Test
    @DisplayName("마지막 실행이 실패한 달은 넣고, 완료된 달과 실패 뒤 완료된 달은 넣지 않는다")
    void failedMonths() throws Exception {
        instance("2026-09", BatchStatus.FAILED);
        instance("2026-08", BatchStatus.FAILED, BatchStatus.COMPLETED);   // 실패했다가 이어서 완료
        instance("2026-10", BatchStatus.COMPLETED);
        stubInstances();

        List<SettlementRunnableMonthResponse> months = useCase.getRunnableMonths(LocalDateTime.of(2026, 11, 10, 9, 0));

        assertEquals(1, months.size());
        assertEquals("2026-09", months.get(0).settlementMonth());
        assertEquals("FAILED", months.get(0).reason());
        assertEquals("FAILED", months.get(0).lastStatus());
    }

    @Test
    @DisplayName("STOPPED는 넣고, 재시작할 수 없는 UNKNOWN·ABANDONED는 넣지 않는다")
    void notRestartableMonths() throws Exception {
        instance("2026-09", BatchStatus.UNKNOWN);
        instance("2026-08", BatchStatus.STOPPED);
        instance("2026-07", BatchStatus.ABANDONED);
        instance("2026-10", BatchStatus.COMPLETED);
        stubInstances();

        List<SettlementRunnableMonthResponse> months = useCase.getRunnableMonths(LocalDateTime.of(2026, 11, 10, 9, 0));

        assertEquals(1, months.size());
        assertEquals("2026-08", months.get(0).settlementMonth());
        assertEquals("STOPPED", months.get(0).lastStatus());
    }

    @Test
    @DisplayName("지난달 실행 기록이 없으면 자동 실행 시각(10일 00:00) + 1시간이 지난 뒤에만 NOT_RUN으로 넣는다")
    void notRunLastMonth() throws Exception {
        stubInstances();

        List<SettlementRunnableMonthResponse> before = useCase.getRunnableMonths(LocalDateTime.of(2026, 11, 10, 0, 30));
        List<SettlementRunnableMonthResponse> after = useCase.getRunnableMonths(LocalDateTime.of(2026, 11, 10, 1, 0));

        assertTrue(before.isEmpty());
        assertEquals(1, after.size());
        assertEquals("2026-10", after.get(0).settlementMonth());
        assertEquals("NOT_RUN", after.get(0).reason());
        assertNull(after.get(0).lastRunAt());
    }

    @Test
    @DisplayName("실행 중인 달과 오래된 미실행 달은 넣지 않는다")
    void runningAndOldMonths() throws Exception {
        instance("2026-10", BatchStatus.STARTED);
        stubInstances();

        assertTrue(useCase.getRunnableMonths(LocalDateTime.of(2026, 11, 20, 9, 0)).isEmpty());
    }

    private void instance(String month, BatchStatus... statuses) {
        JobInstance instance = mock(JobInstance.class);
        List<JobExecution> executions = new ArrayList<>();
        for (BatchStatus status : statuses) {
            JobExecution execution = mock(JobExecution.class);
            JobParameters parameters = new JobParametersBuilder()
                    .addString(SettlementJobNames.PARAM_TARGET_MONTH, month)
                    .toJobParameters();
            when(execution.getId()).thenReturn(nextExecutionId++);
            when(execution.getStatus()).thenReturn(status);
            when(execution.getJobParameters()).thenReturn(parameters);
            when(execution.getStartTime()).thenReturn(LocalDateTime.of(2026, 11, 10, 0, 15));
            executions.add(execution);
        }
        when(jobRepository.getJobExecutions(instance)).thenReturn(executions);
        instances.add(instance);
    }

    private void stubInstances() throws Exception {
        when(jobRepository.getJobInstanceCount(anyString())).thenReturn((long) instances.size());
        when(jobRepository.getJobInstances(anyString(), anyInt(), anyInt())).thenReturn(instances);
    }
}
