package com.backend.boundedContext.settlement.in.scheduler;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

/**
 * 정산 스케줄러 2개
 *   매월 payDayOfMonth(10)일 00:00 KST: 지난달 월 정산 Job 실행
 *   10분마다: 다음 재시도 일시가 지난 FAILED + READY(보류 해제·지급 전에 멈춘 건) 정산서를 최대 retryBatchSize(50)건 지급
 *
 * 재시도 간격(운영 2시간, 테스트 10분)은 실패 기록 때 다음 재시도 일시로 정해지고, 스케줄러는 10분마다 그 시각이 지났는지만 본다.
 * 스케줄러 스레드가 여러 개(spring.task.scheduling.pool.size)라 두 작업이 동시에 돌 수 있다.
 * 재시도는 모든 달의 READY를 대상으로 하므로, 월 정산 Job이 실행 중이면 그 회차를 건너뛰어
 * Job이 방금 만든 READY 정산서를 먼저 지급해 Job 실행 결과(지급 건수·총액)가 적게 잡히지 않게 한다.
 * 겹치더라도 정산서 잠금으로 같은 정산서는 한 번만 지급된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final SettlementFacade settlementFacade;
    // 10/14 리뷰 반영: 예전엔 정적 필드(SettlementPolicy.RETRY_BATCH_SIZE)를 읽어 스프링 밖에서 0이 될 수 있었다.
    // 지금은 정책 빈을 생성자로 주입받아 getRetryBatchSize()로 꺼낸다(값은 인스턴스)
    private final SettlementPolicy settlementPolicy;

    @Scheduled(cron = "0 0 0 ${custom.settlement.payDayOfMonth} * *", zone = "Asia/Seoul")
    public void runMonthlySettlement() {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        try {
            SettlementRunResponse result = settlementFacade.runMonthlyJob(targetMonth, SettlementTrigger.MONTHLY_JOB);
            log.info("월 정산 스케줄 실행 targetMonth={} status={} paid={} failed={}",
                    targetMonth, result.status(), result.paidCount(), result.failedCount());
        } catch (Exception e) {
            // 관리자 화면(실행 결과)에서 실패를 보고 POST /admin/settlements/runs로 수동 실행한다
            log.error("월 정산 스케줄 실행 실패 targetMonth={}", targetMonth, e);
        }
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void retryPayouts() {
        if (settlementFacade.isMonthlyJobRunning()) {
            log.info("월 정산 Job 실행 중이라 이번 정산 재시도 스케줄을 건너뜀");
            return;
        }
        List<Long> settlementIds = settlementFacade.findRetryTargetIds(settlementPolicy.getRetryBatchSize());
        int paid = 0;
        int notPaid = 0;
        for (Long settlementId : settlementIds) {
            try {
                SettlementPayoutResult result =
                        settlementFacade.payout(settlementId, SettlementTrigger.RETRY_SCHEDULER, null);
                if (result.isPaid()) paid++;
                else if (result.processed()) notPaid++;
            } catch (Exception e) {
                notPaid++;
                log.error("정산 재시도 스케줄 오류 settlementId={}", settlementId, e);
            }
        }
        if (!settlementIds.isEmpty()) {
            log.info("정산 재시도 스케줄 targets={} paid={} notPaid={}", settlementIds.size(), paid, notPaid);
        }
    }
}