package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 월 정산 Job 2단계(확정 = 집계)
 * 판별 Step의 판정(Job 실행 컨텍스트)을 판매자마다 넘긴다. 판정이 없는 판매자는 null(금액 확인과 제재 여부만 보고 확정)
 * 재시작한 실행이면 이전 실행의 판정을 쓰지 않는다(SettlementJudgeTasklet.judgementsOf가 null을 돌려준다).
 * 금액 확인은 판정과 상관없이 잠근 후보로 다시 한다(SettlementAggregateUseCase)
 *
 * 한 번 실행에 판매자 BATCH_CHUNK_SIZE(100)명을 처리하고, 남았으면 CONTINUABLE로 다시 불린다
 * 판매자 ID 커서를 Step 실행 컨텍스트에 남기므로 실패한 판매자를 무한 반복하지 않고, 재시작하면 이어서 처리한다
 * 판매자마다 try-catch로 감싸 한 명의 실패가 Step 전체를 멈추지 않게 한다(실패 이력을 남기고, 후보는 READY로 남는다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementAggregateTasklet implements Tasklet {

    private final SettlementFacade settlementFacade;
    // 10/14 리뷰 반영: 예전엔 정적 필드(SettlementPolicy.BATCH_CHUNK_SIZE)를 읽어 스프링 밖에서 0이 될 수 있었다.
    // 지금은 정책 빈을 생성자로 주입받아 getBatchChunkSize()로 꺼낸다(값은 인스턴스)
    private final SettlementPolicy settlementPolicy;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        YearMonth targetMonth = SettlementBatchSupport.targetMonth(contribution);
        Long jobExecutionId = SettlementBatchSupport.jobExecutionId(contribution);
        SettlementTrigger trigger = SettlementBatchSupport.trigger(contribution);
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        long afterSellerId = context.getLong(SettlementJobNames.CURSOR_SELLER_ID, 0L);
        Map<Long, String> judgements = SettlementJudgeTasklet.judgementsOf(
                contribution.getStepExecution().getJobExecution().getExecutionContext(), jobExecutionId);

        List<Long> sellerIds = settlementFacade.findAggregatableSellerIds(targetMonth, afterSellerId,
                settlementPolicy.getBatchChunkSize());
        if (sellerIds.isEmpty()) return RepeatStatus.FINISHED;

        long aggregatedCandidates = 0;
        for (Long sellerId : sellerIds) {
            try {
                String encoded = judgements == null ? null : judgements.get(sellerId);
                SettlementJudgement judgement = encoded == null ? null : SettlementJudgement.decode(encoded);
                int count = settlementFacade.aggregateSeller(sellerId, targetMonth, jobExecutionId, trigger, judgement);
                aggregatedCandidates += count;
                if (count > 0) SettlementBatchSupport.add(context, SettlementJobNames.AGGREGATED_SELLER_COUNT, 1);
            } catch (Exception e) {
                if (SettlementBatchSupport.isClientError(e)) {
                    log.info("정산 집계 실패 sellerId={} targetMonth={} [{}]", sellerId, targetMonth,
                            SettlementBatchSupport.errorCodeOf(e));
                } else {
                    log.error("정산 집계 실패 sellerId={} targetMonth={}", sellerId, targetMonth, e);
                }
                SettlementBatchSupport.add(context, SettlementJobNames.FAILED_SELLER_COUNT, 1);
                settlementFacade.recordAggregateFailure(sellerId, targetMonth, jobExecutionId, trigger,
                        SettlementBatchSupport.errorCodeOf(e), SettlementBatchSupport.reasonOf(e));
            }
        }
        SettlementBatchSupport.add(context, SettlementJobNames.AGGREGATED_CANDIDATE_COUNT, aggregatedCandidates);
        context.putLong(SettlementJobNames.CURSOR_SELLER_ID, sellerIds.get(sellerIds.size() - 1));
        contribution.incrementWriteCount(aggregatedCandidates);   // WRITE_COUNT = 정산서에 묶은 후보 수
        return RepeatStatus.CONTINUABLE;
    }
}