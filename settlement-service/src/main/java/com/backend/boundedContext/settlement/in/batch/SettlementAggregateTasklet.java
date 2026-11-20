package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementConfirmResult;
import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.shared.member.dto.SellerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 월 정산 Job의 확정 Step (Job의 유일한 Step)
 *
 * 한 번 실행에 판매자 BATCH_CHUNK_SIZE(100)명을 처리하고, 남았으면 CONTINUABLE로 다시 불린다.
 * 묶음마다 먼저 트랜잭션 밖에서 판매자 상태를 한 번에 조회하고(회원 HTTP 1번), 원장 불일치 판매자를 읽은 뒤
 * 판매자마다 짧은 트랜잭션으로 "후보 잠금 → 판별 규칙 → 정산서"를 처리한다(SettlementAggregateUseCase.confirmSeller)
 * 판매자 상태 조회는 실패하면 1초, 2초 쉬고 다시 시도한다(최대 3번). 그래도 실패하면(회원 서비스 장애) 실패 이력 1건을 남기고
 * Step을 실패로 끝낸다. 커서를 넘기지 않으므로, 회원 서비스가 복구된 뒤 같은 달을 다시 실행하면 이 묶음부터 이어서 확정한다.
 * (실패를 판매자별 실패로 넘기면 Step이 COMPLETED로 끝나 같은 달을 다시 실행할 수 없고, 묶음 전체가 다음 달 정산까지 밀린다)
 *
 * 판매자 ID 커서를 Step 실행 컨텍스트에 남기므로 실패한 판매자를 무한 반복하지 않고, 재시작하면 이어서 처리한다.
 * 판별은 잠근 후보로 그 자리에서 하므로 재시작해도 이전 실행의 판정을 쓸 일이 없다.
 * 지급은 여기서 하지 않는다. 정산서에 지급 예정일을 정해 두면 지급 스케줄러가 지급한다(SettlementScheduler.retryPayouts)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementAggregateTasklet implements Tasklet {

    /** 판매자 상태 조회 재시도 전 대기(밀리초). 길이 + 1 = 최대 시도 횟수 */
    static final long[] SELLER_STATUS_RETRY_DELAYS_MILLIS = {1_000L, 2_000L};

    private final SettlementFacade settlementFacade;
    private final SettlementPolicy settlementPolicy;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        YearMonth targetMonth = SettlementBatchSupport.targetMonth(contribution);
        Long jobExecutionId = SettlementBatchSupport.jobExecutionId(contribution);
        SettlementTrigger trigger = SettlementBatchSupport.trigger(contribution);
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        long afterSellerId = context.getLong(SettlementJobNames.CURSOR_SELLER_ID, 0L);

        List<Long> sellerIds = settlementFacade.findAggregatableSellerIds(targetMonth, afterSellerId,
                settlementPolicy.getBatchChunkSize());
        if (sellerIds.isEmpty()) return RepeatStatus.FINISHED;

        LocalDate payableOn = SettlementPolicy.payableDateOf(LocalDate.now());
        Set<Long> ledgerMismatchSellerIds = settlementFacade.findLedgerMismatchSellerIds();
        Map<Long, SellerStatus> statuses;
        try {
            statuses = findSellerStatusesWithRetry(sellerIds);
        } catch (Exception e) {
            // 회원 서비스 장애: 이 묶음은 확정하지 않고(후보 READY 유지) 실패 이력 1건을 남긴 뒤 Step을 실패로 끝낸다.
            // 커서는 넘기지 않으므로 같은 달을 다시 실행하면 이 묶음부터 이어서 확정한다
            Long fromSellerId = sellerIds.get(0);
            Long toSellerId = sellerIds.get(sellerIds.size() - 1);
            settlementFacade.recordAggregateChunkFailure(fromSellerId, toSellerId, targetMonth, jobExecutionId, trigger,
                    SettlementBatchSupport.errorCodeOf(e), "판매자 상태 조회 실패로 확정 중단: " + SettlementBatchSupport.reasonOf(e));
            throw new IllegalStateException("정산 확정 판매자 상태 조회 실패 sellers=%d..%d targetMonth=%s"
                    .formatted(fromSellerId, toSellerId, targetMonth), e);
        }

        long confirmedCandidates = 0;
        for (Long sellerId : sellerIds) {
            try {
                SettlementConfirmResult result = settlementFacade.confirmSeller(sellerId, targetMonth, jobExecutionId,
                        trigger, statuses.get(sellerId), ledgerMismatchSellerIds.contains(sellerId), payableOn);
                confirmedCandidates += result.candidateCount();
                if (result.judgement() != null) SettlementBatchSupport.add(context, SettlementJobNames.JUDGED_SELLER_COUNT, 1);
                if (result.judgement() == SettlementJudgement.Type.HOLD) {
                    SettlementBatchSupport.add(context, SettlementJobNames.JUDGE_HOLD_COUNT, 1);
                } else if (result.judgement() == SettlementJudgement.Type.BLOCK) {
                    SettlementBatchSupport.add(context, SettlementJobNames.JUDGE_BLOCK_COUNT, 1);
                }
                if (result.candidateCount() > 0) {
                    SettlementBatchSupport.add(context, SettlementJobNames.AGGREGATED_SELLER_COUNT, 1);
                }
            } catch (Exception e) {
                if (SettlementBatchSupport.isClientError(e)) {
                    log.info("정산 확정 실패 sellerId={} targetMonth={} [{}]", sellerId, targetMonth,
                            SettlementBatchSupport.errorCodeOf(e));
                } else {
                    log.error("정산 확정 실패 sellerId={} targetMonth={}", sellerId, targetMonth, e);
                }
                SettlementBatchSupport.add(context, SettlementJobNames.FAILED_SELLER_COUNT, 1);
                settlementFacade.recordAggregateFailure(sellerId, targetMonth, jobExecutionId, trigger,
                        SettlementBatchSupport.errorCodeOf(e), SettlementBatchSupport.reasonOf(e));
            }
        }
        SettlementBatchSupport.add(context, SettlementJobNames.AGGREGATED_CANDIDATE_COUNT, confirmedCandidates);
        context.putLong(SettlementJobNames.CURSOR_SELLER_ID, sellerIds.get(sellerIds.size() - 1));
        contribution.incrementWriteCount(confirmedCandidates);   // WRITE_COUNT = 정산서에 묶은 후보 수
        return RepeatStatus.CONTINUABLE;
    }

    /** 판매자 상태 조회(회원 HTTP). 실패하면 SELLER_STATUS_RETRY_DELAYS_MILLIS만큼 쉬고 다시 시도하고, 마지막 실패는 그대로 던진다 */
    private Map<Long, SellerStatus> findSellerStatusesWithRetry(List<Long> sellerIds) {
        for (int attempt = 0; ; attempt++) {
            try {
                return settlementFacade.findSellerStatuses(sellerIds);
            } catch (RuntimeException e) {
                if (attempt >= SELLER_STATUS_RETRY_DELAYS_MILLIS.length) throw e;
                long delay = SELLER_STATUS_RETRY_DELAYS_MILLIS[attempt];
                log.warn("정산 확정 판매자 상태 조회 실패, {}ms 뒤 다시 시도 sellers={}..{} attempt={}", delay,
                        sellerIds.get(0), sellerIds.get(sellerIds.size() - 1), attempt + 1, e);
                sleep(delay);
            }
        }
    }

    /** 테스트에서 대기 없이 돌리도록 바꿀 수 있게 따로 둔다 */
    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("판매자 상태 조회 재시도 대기 중 중단됨", e);
        }
    }
}
