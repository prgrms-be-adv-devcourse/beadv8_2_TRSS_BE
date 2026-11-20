package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementConfirmResult;
import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.app.SettlementJobNames;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 확정 Step의 판매자 상태 조회: 실패하면 1초, 2초 쉬고 다시 시도하고(최대 3번),
 * 그래도 실패하면 실패 이력 1건을 남기고 Step을 실패로 끝낸다(커서를 넘기지 않아 같은 달 재실행에서 이 묶음부터 다시)
 */
class SettlementAggregateTaskletTest {

    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 10);
    private static final List<Long> SELLER_IDS = List.of(7L, 8L);

    private final SettlementFacade facade = mock(SettlementFacade.class);
    private final List<Long> sleeps = new ArrayList<>();
    private final SettlementAggregateTasklet tasklet =
            new SettlementAggregateTasklet(facade, new SettlementPolicy(10, 10, 3, 10, 100, 100)) {
                @Override
                void sleep(long millis) {
                    sleeps.add(millis);   // 테스트는 기다리지 않는다
                }
            };

    private StepContribution contribution;

    @BeforeEach
    void setUp() {
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution(new JobParametersBuilder()
                .addString(SettlementJobNames.PARAM_TARGET_MONTH, TARGET_MONTH.toString())
                .addString(SettlementJobNames.PARAM_TRIGGER, SettlementTrigger.MONTHLY_JOB.name())
                .toJobParameters());
        contribution = new StepContribution(stepExecution);
        when(facade.findAggregatableSellerIds(eq(TARGET_MONTH), anyLong(), anyInt())).thenReturn(SELLER_IDS);
        when(facade.findLedgerMismatchSellerIds()).thenReturn(Set.of());
        when(facade.confirmSeller(anyLong(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SettlementConfirmResult(1, null));
    }

    @Test
    @DisplayName("판매자 상태 조회가 두 번 실패해도 1초, 2초 쉬고 세 번째에 성공하면 묶음을 그대로 확정한다")
    void sellerStatus_retriesThenSucceeds() throws Exception {
        when(facade.findSellerStatuses(SELLER_IDS))
                .thenThrow(memberDown())
                .thenThrow(memberDown())
                .thenReturn(Map.of(7L, SellerStatus.APPROVED, 8L, SellerStatus.APPROVED));

        RepeatStatus status = tasklet.execute(contribution, null);

        assertEquals(RepeatStatus.CONTINUABLE, status);
        assertEquals(List.of(1_000L, 2_000L), sleeps);
        verify(facade, times(2)).confirmSeller(anyLong(), any(), any(), any(), any(), anyBoolean(), any());
        assertEquals(8L, cursor());
        verify(facade, never()).recordAggregateChunkFailure(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("판매자 상태 조회가 세 번 모두 실패하면 실패 이력 1건을 남기고 Step을 실패로 끝내며, 커서를 넘기지 않고 확정하지 않는다")
    void sellerStatus_allAttemptsFail_failsStepWithoutMovingCursor() {
        when(facade.findSellerStatuses(SELLER_IDS)).thenThrow(memberDown());

        assertThrows(IllegalStateException.class, () -> tasklet.execute(contribution, null));

        verify(facade, times(3)).findSellerStatuses(SELLER_IDS);
        assertEquals(List.of(1_000L, 2_000L), sleeps);
        verify(facade).recordAggregateChunkFailure(eq(7L), eq(8L), eq(TARGET_MONTH), any(),
                eq(SettlementTrigger.MONTHLY_JOB), eq("502-EXTERNAL_SERVICE_ERROR"), anyString());
        verify(facade, never()).recordAggregateFailure(any(), any(), any(), any(), any(), any());
        verify(facade, never()).confirmSeller(anyLong(), any(), any(), any(), any(), anyBoolean(), any());
        assertFalse(contribution.getStepExecution().getExecutionContext()
                .containsKey(SettlementJobNames.CURSOR_SELLER_ID));   // 같은 달 재실행이 이 묶음부터
        assertEquals(0L, contribution.getStepExecution().getExecutionContext()
                .getLong(SettlementJobNames.FAILED_SELLER_COUNT, 0L));
    }

    private long cursor() {
        return contribution.getStepExecution().getExecutionContext().getLong(SettlementJobNames.CURSOR_SELLER_ID);
    }

    private static DomainException memberDown() {
        return new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR);
    }
}
