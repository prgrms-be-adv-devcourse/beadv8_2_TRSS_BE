package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 월 정산 Job 1단계(판별)
 *
 * 확정할 판매자(READY 후보가 있는 판매자)마다 판별 규칙 체인을 돌려 PASS·HOLD·BLOCK을 정한다.
 * 판정은 Step 실행 컨텍스트에 판매자 ID → 판정 문자열로 모으고, Step이 끝나면 ExecutionContextPromotionListener가
 * Job 실행 컨텍스트로 올린다. 확정 Step이 그 판정을 읽어 정산서를 만든다.
 * 판매자 ID 커서로 끊어 처리한다. 다만 재시작한 실행이면(Step 실행 컨텍스트의 판정이 이전 실행에서 만든 것이면)
 * 커서와 판정을 비우고 처음부터 다시 판별한다. 며칠 뒤 재시작해도 그 시점의 판매자 상태와 후보로 판정하기 위해서다.
 * 판별은 이력만 남기는 읽기 작업이라 다시 실행해도 금액에 영향이 없다.
 * 한 판매자의 판별 실패는 맵에 넣지 않는다 → 확정 Step이 판정 없이(금액 확인과 제재 여부만 보고) 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementJudgeTasklet implements Tasklet {

    private final SettlementFacade settlementFacade;
    private final SettlementPolicy settlementPolicy;

    @Override
    @SuppressWarnings("unchecked")
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        YearMonth targetMonth = SettlementBatchSupport.targetMonth(contribution);
        Long jobExecutionId = SettlementBatchSupport.jobExecutionId(contribution);
        SettlementTrigger trigger = SettlementBatchSupport.trigger(contribution);
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        resetIfRestarted(context, jobExecutionId);
        long afterSellerId = context.getLong(SettlementJobNames.CURSOR_SELLER_ID, 0L);

        HashMap<Long, String> judgements = context.containsKey(SettlementJobNames.JUDGEMENTS)
                ? (HashMap<Long, String>) context.get(SettlementJobNames.JUDGEMENTS)
                : new HashMap<>();
        // 판정이 하나도 없어도 키는 남긴다(확정 Step이 "판별을 했다"는 것을 알 수 있게)
        context.put(SettlementJobNames.JUDGEMENTS, judgements);

        List<Long> sellerIds = settlementFacade.findAggregatableSellerIds(targetMonth, afterSellerId,
                settlementPolicy.getBatchChunkSize());
        if (sellerIds.isEmpty()) return RepeatStatus.FINISHED;

        Set<Long> ledgerMismatchSellerIds = settlementFacade.findLedgerMismatchSellerIds();
        for (Long sellerId : sellerIds) {
            try {
                SettlementJudgement judgement = settlementFacade.judgeSeller(sellerId, targetMonth,
                        ledgerMismatchSellerIds, trigger, jobExecutionId);
                judgements.put(sellerId, judgement.encode());
                SettlementBatchSupport.add(context, SettlementJobNames.JUDGED_SELLER_COUNT, 1);
                count(context, judgement);
            } catch (Exception e) {
                if (SettlementBatchSupport.isClientError(e)) {
                    log.info("정산 판별 실패 sellerId={} targetMonth={} [{}]", sellerId, targetMonth,
                            SettlementBatchSupport.errorCodeOf(e));
                } else {
                    log.error("정산 판별 실패 sellerId={} targetMonth={}", sellerId, targetMonth, e);
                }
                SettlementBatchSupport.add(context, SettlementJobNames.JUDGE_FAILED_COUNT, 1);
            }
        }
        context.put(SettlementJobNames.JUDGEMENTS, judgements);
        context.putLong(SettlementJobNames.CURSOR_SELLER_ID, sellerIds.get(sellerIds.size() - 1));
        return RepeatStatus.CONTINUABLE;
    }

    /**
     * 이전 실행에서 만든 판정·커서·건수면 버리고 처음부터 판별한다(재시작 시 예전 판정 재사용 방지)
     * 건수도 0으로 되돌린다. 남겨 두면 다시 판별한 판매자가 두 번 세지고, 이전 실행의 실패 건수 때문에
     * 이번 판별에 실패가 없어도 Step이 COMPLETED WITH FAILURES로 끝난다(SettlementStepFailureListener)
     */
    static void resetIfRestarted(ExecutionContext context, long jobExecutionId) {
        if (context.getLong(SettlementJobNames.JUDGED_EXECUTION_ID, -1L) == jobExecutionId) return;
        context.put(SettlementJobNames.JUDGEMENTS, new HashMap<Long, String>());
        context.putLong(SettlementJobNames.CURSOR_SELLER_ID, 0L);
        context.putLong(SettlementJobNames.JUDGED_SELLER_COUNT, 0L);
        context.putLong(SettlementJobNames.JUDGE_HOLD_COUNT, 0L);
        context.putLong(SettlementJobNames.JUDGE_BLOCK_COUNT, 0L);
        context.putLong(SettlementJobNames.JUDGE_FAILED_COUNT, 0L);
        context.putLong(SettlementJobNames.JUDGED_EXECUTION_ID, jobExecutionId);
    }

    private static void count(ExecutionContext context, SettlementJudgement judgement) {
        switch (judgement.type()) {
            case HOLD -> SettlementBatchSupport.add(context, SettlementJobNames.JUDGE_HOLD_COUNT, 1);
            case BLOCK -> SettlementBatchSupport.add(context, SettlementJobNames.JUDGE_BLOCK_COUNT, 1);
            case PASS -> { }
        }
    }

    /**
     * 확정 Step이 Job 실행 컨텍스트에서 판정을 읽는다.
     * 판별을 하지 않았거나, 판정이 지금 실행(jobExecutionId)이 아닌 이전 실행에서 만든 것이면 null(모든 판매자를 "판정 없음"으로 처리)
     */
    @SuppressWarnings("unchecked")
    static Map<Long, String> judgementsOf(ExecutionContext jobContext, long jobExecutionId) {
        if (!jobContext.containsKey(SettlementJobNames.JUDGEMENTS)) return null;
        if (jobContext.getLong(SettlementJobNames.JUDGED_EXECUTION_ID, -1L) != jobExecutionId) return null;
        return (Map<Long, String>) jobContext.get(SettlementJobNames.JUDGEMENTS);
    }
}
