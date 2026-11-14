package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.in.dto.SettlementRunnableMonthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 관리자 월 정산 수동 실행 확인 창의 "실행할 달" 목록
 *
 * 수동 실행은 자동 실행이 실패했거나 실행되지 않은 끝난 달에만 쓰는 최후의 수단이라, 그런 달만 돌려준다.
 *   FAILED  : 최근 maxSearchMonths개월 안에서 그 달의 마지막 실행이 FAILED·STOPPED (같은 달로 다시 실행하면 멈춘 Step부터 이어서)
 *   NOT_RUN : 지난달인데 자동 실행 시각(이번 달 payDayOfMonth일 00:00) + 1시간이 지났는데도 실행 기록이 없음
 * 그보다 오래된 미실행 달은 넣지 않는다. 다음 달 실행이 정산월 ≤ 대상월 후보를 함께 묶어 가므로(이월) 따로 돌릴 필요가 없다.
 * 실행 중인 달, ABANDONED, UNKNOWN은 넣지 않는다(Spring Batch가 재시작을 거절한다. UNKNOWN은 롤백하지 못한 실패라 배치 테이블을 직접 확인해야 한다).
 * 최근 달부터 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class SettlementGetRunnableMonthsUseCase {

    static final String FAILED = "FAILED";
    static final String NOT_RUN = "NOT_RUN";
    private static final Set<BatchStatus> RESTARTABLE = Set.of(BatchStatus.FAILED, BatchStatus.STOPPED);
    /** 자동 실행이 시작되고 나서 "실행되지 않았다"고 볼 때까지 기다리는 시간 */
    private static final long NOT_RUN_GRACE_HOURS = 1;

    private final JobRepository jobRepository;
    private final SettlementPolicy settlementPolicy;

    public List<SettlementRunnableMonthResponse> getRunnableMonths(LocalDateTime now) {
        YearMonth lastClosed = YearMonth.from(now).minusMonths(1);
        YearMonth oldest = lastClosed.minusMonths(settlementPolicy.getMaxSearchMonths() - 1L);
        Map<String, JobExecution> latestByMonth = latestExecutionByMonth();

        List<SettlementRunnableMonthResponse> result = new ArrayList<>();
        for (YearMonth month = lastClosed; !month.isBefore(oldest); month = month.minusMonths(1)) {
            JobExecution latest = latestByMonth.get(month.toString());
            if (latest == null) {
                if (month.equals(lastClosed) && isAutoRunOverdue(lastClosed, now)) {
                    result.add(new SettlementRunnableMonthResponse(month.toString(), NOT_RUN, null, null));
                }
                continue;
            }
            if (RESTARTABLE.contains(latest.getStatus())) {
                result.add(new SettlementRunnableMonthResponse(month.toString(), FAILED,
                        latest.getStatus().name(), latest.getStartTime()));
            }
        }
        return result;
    }

    /** 정산월 → 그 달의 마지막 실행 (실행 ID가 가장 큰 것) */
    private Map<String, JobExecution> latestExecutionByMonth() {
        Map<String, JobExecution> latest = new HashMap<>();
        for (JobInstance instance : jobRepository.getJobInstances(SettlementJobNames.JOB_NAME, 0,
                (int) countInstances())) {
            jobRepository.getJobExecutions(instance).stream()
                    .max(Comparator.comparingLong(JobExecution::getId))
                    .ifPresent(execution -> {
                        String month = execution.getJobParameters().getString(SettlementJobNames.PARAM_TARGET_MONTH);
                        if (month == null) return;
                        JobExecution current = latest.get(month);
                        if (current == null || current.getId() < execution.getId()) latest.put(month, execution);
                    });
        }
        return latest;
    }

    /** 지난달 자동 실행 시각(이번 달 payDayOfMonth일 00:00)에서 1시간이 지났는지 */
    private boolean isAutoRunOverdue(YearMonth lastClosed, LocalDateTime now) {
        LocalDateTime scheduledAt = lastClosed.plusMonths(1).atDay(settlementPolicy.getPayDayOfMonth()).atStartOfDay();
        return !now.isBefore(scheduledAt.plusHours(NOT_RUN_GRACE_HOURS));
    }

    private long countInstances() {
        try {
            return jobRepository.getJobInstanceCount(SettlementJobNames.JOB_NAME);
        } catch (NoSuchJobException e) {
            return 0L;   // 아직 한 번도 실행하지 않음
        }
    }
}
