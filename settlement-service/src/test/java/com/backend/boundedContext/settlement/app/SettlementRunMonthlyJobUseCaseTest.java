package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementRunStartResponse;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.MetaDataInstanceFactory;

import java.time.YearMonth;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 월 정산 수동 실행 접수: 같은 달을 이미 접수했으면(대기 중이거나 실행 중) 409, 다른 달이 아직 안 끝났으면 QUEUED
 */
class SettlementRunMonthlyJobUseCaseTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final YearMonth OTHER_MONTH = YearMonth.of(2026, 8);

    private final JobOperator jobOperator = mock(JobOperator.class);
    private final JobRepository jobRepository = mock(JobRepository.class);   // JobInstance 없음 = 처음 실행하는 달
    private final SettlementGetRunsUseCase getRunsUseCase = mock(SettlementGetRunsUseCase.class);
    private final SettlementRunMonthlyJobUseCase useCase =
            new SettlementRunMonthlyJobUseCase(jobOperator, mock(Job.class), getRunsUseCase, jobRepository);
    /** 열기 전까지 Job 실행이 끝나지 않는다 */
    private final CountDownLatch jobRunning = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        jobRunning.countDown();
        useCase.shutdown();
    }

    @Test
    @DisplayName("같은 달 요청이 연달아 오면 첫 요청만 접수하고 두 번째는 409 SETTLEMENT_RUNNING이며, Job은 한 번만 시작한다")
    void sameMonthTwice_secondIs409() throws Exception {
        blockJobUntilReleased();

        SettlementRunStartResponse first = useCase.start(MONTH, SettlementTrigger.ADMIN);
        DomainException second = assertThrows(DomainException.class, () -> useCase.start(MONTH, SettlementTrigger.ADMIN));

        assertEquals("STARTED", first.status());
        assertEquals("409-SETTLEMENT_RUNNING", second.getResultCode());
        jobRunning.countDown();
        verify(jobOperator, timeout(2_000).times(1)).start(any(Job.class), any());
    }

    @Test
    @DisplayName("다른 달 수동 실행이 아직 끝나지 않았으면 접수하되 QUEUED로 알려 준다")
    void otherMonthRunning_queued() throws Exception {
        blockJobUntilReleased();

        useCase.start(MONTH, SettlementTrigger.ADMIN);
        SettlementRunStartResponse other = useCase.start(OTHER_MONTH, SettlementTrigger.ADMIN);

        assertEquals("QUEUED", other.status());
    }

    @Test
    @DisplayName("앞의 실행이 끝나면 같은 달을 다시 접수할 수 있다(실패한 달 재실행)")
    void afterRunEnds_sameMonthAcceptedAgain() throws Exception {
        when(jobOperator.start(any(Job.class), any())).thenReturn(MetaDataInstanceFactory.createJobExecution());

        useCase.start(MONTH, SettlementTrigger.ADMIN);
        verify(getRunsUseCase, timeout(2_000)).toResponse(any());
        SettlementRunStartResponse again = waitUntilAccepted();

        assertEquals("STARTED", again.status());
        verify(jobOperator, timeout(2_000).times(2)).start(any(Job.class), any());
    }

    private void blockJobUntilReleased() throws Exception {
        when(jobOperator.start(any(Job.class), any())).thenAnswer(inv -> {
            assertTrue(jobRunning.await(5, TimeUnit.SECONDS));
            return MetaDataInstanceFactory.createJobExecution();
        });
    }

    /** 실행 스레드가 접수 표시를 지우는 것(finally)은 결과 조회 직후라 잠깐 기다릴 수 있다 */
    private SettlementRunStartResponse waitUntilAccepted() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                return useCase.start(MONTH, SettlementTrigger.ADMIN);
            } catch (DomainException e) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("앞의 실행이 끝난 뒤에도 같은 달을 접수하지 못했습니다");
    }
}
