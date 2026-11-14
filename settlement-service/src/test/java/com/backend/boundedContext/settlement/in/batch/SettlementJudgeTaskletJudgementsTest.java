package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 확정 Step이 Job 실행 컨텍스트의 판정을 읽을 때, 지금 실행에서 만든 판정만 쓰는지 확인한다(코드리뷰 변경 제안 8)
 * 판별 Step을 재시작하면 이전 실행의 판정·커서·건수를 비우는지도 확인한다
 */
class SettlementJudgeTaskletJudgementsTest {

    @Test
    @DisplayName("지금 실행에서 만든 판정이면 그대로 읽는다")
    void sameExecution_returnsJudgements() {
        ExecutionContext jobContext = jobContext(31L);

        Map<Long, String> judgements = SettlementJudgeTasklet.judgementsOf(jobContext, 31L);

        assertEquals(SettlementJudgement.Type.PASS, SettlementJudgement.decode(judgements.get(7L)).type());
    }

    @Test
    @DisplayName("재시작한 실행이면 이전 실행의 판정을 쓰지 않는다(null = 모든 판매자를 판정 없음으로 처리)")
    void restartedExecution_ignoresOldJudgements() {
        ExecutionContext jobContext = jobContext(31L);

        assertNull(SettlementJudgeTasklet.judgementsOf(jobContext, 32L));
    }

    @Test
    @DisplayName("판별을 하지 않은 실행이면 null")
    void noJudgeStep_returnsNull() {
        assertNull(SettlementJudgeTasklet.judgementsOf(new ExecutionContext(), 31L));
    }

    @Test
    @DisplayName("재시작한 실행이면 판정·커서와 함께 판별 건수도 0으로 되돌린다(이전 실행의 실패 건수가 남지 않게)")
    void restartedExecution_resetsStepContext() {
        ExecutionContext stepContext = jobContext(31L);
        stepContext.putLong(SettlementJobNames.CURSOR_SELLER_ID, 250L);
        stepContext.putLong(SettlementJobNames.JUDGED_SELLER_COUNT, 200L);
        stepContext.putLong(SettlementJobNames.JUDGE_HOLD_COUNT, 3L);
        stepContext.putLong(SettlementJobNames.JUDGE_BLOCK_COUNT, 2L);
        stepContext.putLong(SettlementJobNames.JUDGE_FAILED_COUNT, 1L);

        SettlementJudgeTasklet.resetIfRestarted(stepContext, 32L);

        assertTrue(((Map<?, ?>) stepContext.get(SettlementJobNames.JUDGEMENTS)).isEmpty());
        assertEquals(0L, stepContext.getLong(SettlementJobNames.CURSOR_SELLER_ID));
        assertEquals(0L, stepContext.getLong(SettlementJobNames.JUDGED_SELLER_COUNT));
        assertEquals(0L, stepContext.getLong(SettlementJobNames.JUDGE_HOLD_COUNT));
        assertEquals(0L, stepContext.getLong(SettlementJobNames.JUDGE_BLOCK_COUNT));
        assertEquals(0L, stepContext.getLong(SettlementJobNames.JUDGE_FAILED_COUNT));
        assertEquals(32L, stepContext.getLong(SettlementJobNames.JUDGED_EXECUTION_ID));
    }

    @Test
    @DisplayName("같은 실행 안에서 다시 불리면(다음 묶음) 판정·커서·건수를 그대로 둔다")
    void sameExecution_keepsStepContext() {
        ExecutionContext stepContext = jobContext(31L);
        stepContext.putLong(SettlementJobNames.CURSOR_SELLER_ID, 250L);
        stepContext.putLong(SettlementJobNames.JUDGE_FAILED_COUNT, 1L);

        SettlementJudgeTasklet.resetIfRestarted(stepContext, 31L);

        assertEquals(1, ((Map<?, ?>) stepContext.get(SettlementJobNames.JUDGEMENTS)).size());
        assertEquals(250L, stepContext.getLong(SettlementJobNames.CURSOR_SELLER_ID));
        assertEquals(1L, stepContext.getLong(SettlementJobNames.JUDGE_FAILED_COUNT));
    }

    private static ExecutionContext jobContext(long judgedExecutionId) {
        HashMap<Long, String> judgements = new HashMap<>();
        judgements.put(7L, SettlementJudgement.pass().encode());
        ExecutionContext context = new ExecutionContext();
        context.put(SettlementJobNames.JUDGEMENTS, judgements);
        context.putLong(SettlementJobNames.JUDGED_EXECUTION_ID, judgedExecutionId);
        return context;
    }
}
