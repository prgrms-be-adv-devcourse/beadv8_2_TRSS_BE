package com.backend.boundedContext.settlement.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 강제 종료로 STARTED에 남은 월 정산 실행을 앱 기동 때 FAILED로 정리해 다시 실행할 수 있게 만든다
 */
class SettlementRecoverJobUseCaseTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobOperator jobOperator = mock(JobOperator.class);
    private final SettlementRecoverJobUseCase useCase = new SettlementRecoverJobUseCase(jobRepository, jobOperator);

    @Test
    @DisplayName("STARTED로 남은 월 정산 실행을 모두 recover한다")
    void recoverStartedExecutions() {
        JobExecution first = mock(JobExecution.class);
        JobExecution second = mock(JobExecution.class);
        when(jobRepository.findRunningJobExecutions(SettlementJobNames.JOB_NAME)).thenReturn(Set.of(first, second));

        int recovered = useCase.recoverInterruptedJobs();

        assertEquals(2, recovered);
        verify(jobOperator).recover(first);
        verify(jobOperator).recover(second);
    }

    @Test
    @DisplayName("멈춘 실행이 없으면 아무것도 하지 않는다")
    void nothingToRecover() {
        when(jobRepository.findRunningJobExecutions(SettlementJobNames.JOB_NAME)).thenReturn(Set.of());

        assertEquals(0, useCase.recoverInterruptedJobs());
        verify(jobOperator, never()).recover(org.mockito.ArgumentMatchers.any());
    }
}
