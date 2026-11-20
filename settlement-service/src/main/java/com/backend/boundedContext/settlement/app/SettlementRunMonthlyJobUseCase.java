package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import com.backend.global.exception.DomainException;
import com.backend.boundedContext.settlement.in.dto.SettlementRunStartResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * 월 정산 Job(확정 Step)을 실행한다. 매월 10일 스케줄러는 run(동기), 관리자 수동 실행은 start(비동기)를 쓴다.
 *   정산월만 Job 파라미터로 쓴다. 같은 달이 실행 중이면 409 SETTLEMENT_RUNNING, 이미 완료된 달이면 409 SETTLEMENT_ALREADY_COMPLETED
 *   실패로 끝난 달은 같은 파라미터로 다시 실행하면 실패한 Step부터 이어서 실행된다(Spring Batch 재시작)
 *   서버가 강제 종료돼 STARTED로 남은 실행은 앱 기동 때 SettlementRecoverJobUseCase가 FAILED로 정리하므로 같은 방식으로 이어서 실행된다
 *
 * run은 Job이 끝날 때까지 기다린 뒤 결과를 돌려준다(스케줄러용).
 * start는 바로 실행할 수 있는지(끝난 달인지, 실행 중이거나 이미 완료되지 않았는지)만 확인하고 Job을 별도 스레드에서 시작한 뒤
 * 곧바로 돌아온다(관리자용, 202). 판매자가 많아 Job이 nginx 응답 대기 시간(60초)을 넘겨도 요청이 끊기지 않게 하기 위해서다.
 * 결과는 실행 기록 조회(GET /runs)로 본다.
 */
@Slf4j
@Component
public class SettlementRunMonthlyJobUseCase {

    private final JobOperator jobOperator;
    private final Job settlementMonthlyJob;
    private final SettlementGetRunsUseCase settlementGetRunsUseCase;
    private final JobRepository jobRepository;
    /** 관리자 수동 실행용 스레드 1개. 같은 시간에 수동 실행은 하나만 돈다 */
    private final ExecutorService adminRunExecutor =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "settlement-admin-run"));
    /** 접수했지만 아직 끝나지 않은(대기 중이거나 실행 중인) 수동 실행의 정산월. 같은 달을 두 번 접수하지 않게 한다 */
    private final Set<YearMonth> acceptedMonths = ConcurrentHashMap.newKeySet();

    /** Job 빈은 Tasklet → Facade → 이 UseCase를 거쳐 다시 자신을 참조하므로 @Lazy로 순환을 끊는다 */
    public SettlementRunMonthlyJobUseCase(JobOperator jobOperator,
                                          @Lazy @Qualifier(SettlementJobNames.JOB_NAME) Job settlementMonthlyJob,
                                          SettlementGetRunsUseCase settlementGetRunsUseCase,
                                          JobRepository jobRepository) {
        this.jobOperator = jobOperator;
        this.settlementMonthlyJob = settlementMonthlyJob;
        this.settlementGetRunsUseCase = settlementGetRunsUseCase;
        this.jobRepository = jobRepository;
    }

    /**
     * 관리자 수동 실행(비동기). 실행할 수 있는지만 먼저 확인하고 Job은 별도 스레드에서 시작한다
     * 확인과 접수는 한 번에 한 요청만 한다(synchronized). 같은 달을 이미 접수했으면(대기 중이거나 실행 중) 409 SETTLEMENT_RUNNING이라,
     * 연달아 온 요청(더블클릭)이 둘 다 접수돼 실패한 달을 아무도 모르게 다시 시작하거나 409가 로그로만 남는 일이 없다.
     * 다른 달 수동 실행이 아직 끝나지 않았으면 접수하되 status를 QUEUED로 알려 준다(스레드가 1개라 앞의 실행이 끝난 뒤 시작한다).
     * 스케줄러 실행(run)과 같은 달이 겹치는 경우는 Spring Batch가 막는다(실행 중이면 409로 끝나고 로그로만 남음. 드묾)
     */
    public synchronized SettlementRunStartResponse start(YearMonth targetMonth, SettlementTrigger trigger) {
        if (!SettlementPolicy.isClosedMonth(targetMonth, LocalDateTime.now())) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_MONTH_INVALID);
        }
        if (acceptedMonths.contains(targetMonth)) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_RUNNING);
        }
        JobInstance instance = jobRepository.getJobInstance(SettlementJobNames.JOB_NAME, identifying(targetMonth));
        JobExecution last = instance == null ? null : jobRepository.getJobExecutions(instance).stream()
                .max(java.util.Comparator.comparingLong(JobExecution::getId))
                .orElse(null);
        if (last != null && last.isRunning()) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_RUNNING);
        }
        if (last != null && last.getStatus() == BatchStatus.COMPLETED) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_ALREADY_COMPLETED);
        }
        boolean queued = !acceptedMonths.isEmpty();   // 다른 달 수동 실행이 아직 끝나지 않음
        acceptedMonths.add(targetMonth);
        adminRunExecutor.execute(() -> {
            try {
                run(targetMonth, trigger);
            } catch (DomainException e) {
                if (e.statusCode() < 500) {
                    log.info("월 정산 수동 실행 안 함 targetMonth={} [{}]", targetMonth, e.getResultCode());
                } else {
                    log.error("월 정산 수동 실행 실패 targetMonth={}", targetMonth, e);
                }
            } catch (RuntimeException e) {
                log.error("월 정산 수동 실행 실패 targetMonth={}", targetMonth, e);
            } finally {
                acceptedMonths.remove(targetMonth);
            }
        });
        String status = queued ? "QUEUED" : "STARTED";
        log.info("월 정산 수동 실행 접수 targetMonth={} status={}", targetMonth, status);
        return new SettlementRunStartResponse(targetMonth.toString(), status);
    }

    @PreDestroy
    void shutdown() {
        adminRunExecutor.shutdown();
    }

    private static JobParameters identifying(YearMonth targetMonth) {
        return new JobParametersBuilder()
                .addString(SettlementJobNames.PARAM_TARGET_MONTH, targetMonth.toString())
                .toJobParameters();
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
            if (execution.getStatus() == BatchStatus.FAILED) {
                // 멈춘 달은 사람이 다시 실행해야 끝난다. 알림(Slack)을 받도록 ERROR로 남긴다. 원인은 처리 이력·위 ERROR 로그
                log.error("월 정산 Job 실패로 멈춤 targetMonth={} executionId={} trigger={}. 원인을 해결한 뒤 관리자 화면에서 같은 달을 다시 실행해 주세요",
                        targetMonth, execution.getId(), trigger);
            } else {
                log.info("월 정산 Job 종료 targetMonth={} executionId={} status={}",
                        targetMonth, execution.getId(), execution.getStatus());
            }
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