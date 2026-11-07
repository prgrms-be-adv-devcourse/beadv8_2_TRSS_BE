package com.backend.boundedContext.settlement.in.scheduler;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 정산 스케줄러 3개
 *   매일 03:35 KST: 원장 재대조(지난 lookbackDays(2)일 동안 지급된 정산서 = T+1 + 풀리지 않은 불일치 정산서) 후 원장 불일치 보류 정리.
 *                   custom.settlement.ledgerRecheck.enabled일 때만
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
        } catch (DomainException e) {
            // 로그 규칙(exception_handling 9장): 4xx는 INFO·스택 없음(예: 관리자가 먼저 실행해 409 이미 완료된 달), 5xx는 ERROR
            if (e.statusCode() < 500) {
                log.info("월 정산 스케줄 실행 안 함 targetMonth={} [{}]", targetMonth, e.getResultCode());
            } else {
                log.error("월 정산 스케줄 실행 실패 targetMonth={}", targetMonth, e);
            }
        } catch (Exception e) {
            // 관리자 화면(실행 결과)에서 실패를 보고 POST /admin/settlements/runs로 수동 실행한다
            log.error("월 정산 스케줄 실행 실패 targetMonth={}", targetMonth, e);
        }
    }

    /**
     * 원장 무결성 2단계 ②: 매일 새벽 지급 영수증 재대조(T+1)
     * ① 지난 lookbackDays일 동안 지급된 정산서 ② 지급일과 상관없이 불일치가 아직 풀리지 않은 정산서(①에서 본 것은 빼고)
     * ②를 매일 다시 봐야 원장이 고쳐졌을 때 불일치가 풀리고, 그 사유로 보류된 정산서가 자동 해제된다
     * ③ 정리: 불일치가 없는 판매자인데 원장 불일치 사유로 남은 보류를 푼다(재시작한 Job의 예전 판정, 해소와 동시에 생긴 보류)
     * 재시도 스케줄러(10분마다 정각 0·10·20…분)와 같은 시각에 돌면 지급 직전 보류와 해소가 엇갈리므로 35분에 돈다
     * 월 정산 Job 안에서는 재대조하지 않는다. 불일치 이력은 다음 Job의 판별 Step(LedgerMismatchRule)이 읽어 지급을 보류한다
     * 원장을 읽고 이력만 남기며 정산서 상태는 바꾸지 않으므로, 월 정산 Job이 실행 중이어도 건너뛰지 않는다
     */
    @Scheduled(cron = "0 35 3 * * *", zone = "Asia/Seoul")
    public void recheckLedgers() {
        if (!settlementPolicy.isLedgerRecheckEnabled()) return;
        LocalDateTime since = LocalDateTime.now().minusDays(settlementPolicy.getLedgerRecheckLookbackDays());
        long afterId = 0L;
        int checked = 0;
        int mismatched = 0;
        int errors = 0;
        Set<Long> seen = new HashSet<>();
        while (true) {
            List<Long> settlementIds = settlementFacade.findLedgerRecheckTargetIds(since, afterId,
                    settlementPolicy.getBatchChunkSize());
            if (settlementIds.isEmpty()) break;
            for (Long settlementId : settlementIds) {
                seen.add(settlementId);
                Boolean mismatch = recheckLedger(settlementId);
                if (mismatch == null) errors++;
                else {
                    checked++;
                    if (mismatch) mismatched++;
                }
            }
            afterId = settlementIds.get(settlementIds.size() - 1);
        }
        int unresolvedChecked = 0;
        for (Long settlementId : settlementFacade.findUnresolvedLedgerRecheckTargetIds()) {
            if (!seen.add(settlementId)) continue;
            Boolean mismatch = recheckLedger(settlementId);
            if (mismatch == null) errors++;
            else {
                unresolvedChecked++;
                if (mismatch) mismatched++;
            }
        }
        int released = 0;
        for (Long sellerId : settlementFacade.findSellerIdsWithLedgerMismatchHolds()) {
            try {
                released += settlementFacade.releaseLedgerMismatchHoldsIfResolved(sellerId,
                        SettlementTrigger.LEDGER_RECHECK);
            } catch (Exception e) {
                errors++;
                log.error("원장 불일치 보류 정리 오류 sellerId={}", sellerId, e);
            }
        }
        log.info("원장 재대조 스케줄 checked={} unresolvedChecked={} mismatched={} released={} errors={}", checked,
                unresolvedChecked, mismatched, released, errors);
    }

    /** 정산서 1건 재대조. 불일치면 true, 같으면 false, 오류면 null(다음 회차에 다시 본다) */
    private Boolean recheckLedger(Long settlementId) {
        try {
            return settlementFacade.recheckLedger(settlementId, SettlementTrigger.LEDGER_RECHECK, null).isMismatch();
        } catch (Exception e) {
            log.error("원장 재대조 스케줄 오류 settlementId={}", settlementId, e);
            return null;
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