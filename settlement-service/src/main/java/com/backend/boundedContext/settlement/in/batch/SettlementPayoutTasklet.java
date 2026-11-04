package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
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

/**
 * 월 정산 Job 2단계(지급)
 *
 * 대상월의 READY 정산서를 BATCH_CHUNK_SIZE건씩 지급한다.
 * 실패한 정산서는 FAILED로 남고 이후 재시도 스케줄러가 처리한다. 정산서 ID 커서로 한 번씩만 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementPayoutTasklet implements Tasklet {

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
        long afterSettlementId = context.getLong(SettlementJobNames.CURSOR_SETTLEMENT_ID, 0L);

        List<Long> settlementIds = settlementFacade.findMonthlyPayoutTargetIds(targetMonth, afterSettlementId,
                settlementPolicy.getBatchChunkSize());
        if (settlementIds.isEmpty()) return RepeatStatus.FINISHED;

        long paid = 0;
        for (Long settlementId : settlementIds) {
            try {
                SettlementPayoutResult result =
                        settlementFacade.payout(settlementId, trigger, jobExecutionId);
                if (result.isPaid()) paid++;
                record(context, result);
            } catch (Exception e) {
                SettlementBatchSupport.add(context, SettlementJobNames.FAILED_COUNT, 1);
                log.error("정산 지급 중 예상하지 못한 오류 settlementId={}", settlementId, e);
            }
        }
        context.putLong(SettlementJobNames.CURSOR_SETTLEMENT_ID, settlementIds.get(settlementIds.size() - 1));
        contribution.incrementWriteCount(paid);   // WRITE_COUNT = 지급 완료(PAID)한 정산서 수
        return RepeatStatus.CONTINUABLE;
    }

    private static void record(ExecutionContext context, SettlementPayoutResult result) {
        if (!result.processed()) {
            SettlementBatchSupport.add(context, SettlementJobNames.SKIPPED_COUNT, 1);
        } else if (result.status() == SettlementStatus.PAID) {
            SettlementBatchSupport.add(context, SettlementJobNames.PAID_COUNT, 1);
            SettlementBatchSupport.add(context, SettlementJobNames.PAYOUT_TOTAL, result.payoutAmount());
            SettlementBatchSupport.add(context, SettlementJobNames.FEE_TOTAL, result.totalFee());
        } else if (result.status() == SettlementStatus.MANUAL_REQUIRED) {
            SettlementBatchSupport.add(context, SettlementJobNames.MANUAL_REQUIRED_COUNT, 1);
        } else {
            SettlementBatchSupport.add(context, SettlementJobNames.FAILED_COUNT, 1);
        }
    }
}