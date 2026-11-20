package com.backend.boundedContext.settlement.in.scheduler;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지급 스케줄러: 한 회차 안에서 대상이 없을 때까지 페이지를 이어 읽어 지급하고(ID 커서),
 * 시간 한도(8분)를 넘기거나 월 정산 Job이 시작되면 멈춘다
 */
class SettlementSchedulerRetryPayoutsTest {

    /** 페이지 크기 2 */
    private static final SettlementPolicy POLICY = new SettlementPolicy(10, 10, 3, 10, 100, 2);

    private final SettlementFacade facade = mock(SettlementFacade.class);
    /** 지급 대상. 지급해도 목록에서 빼지 않는다(지급하지 못하고 대상에 남은 건과 같은 상황) */
    private final List<Long> targets = new ArrayList<>(List.of(1L, 2L, 3L, 4L, 5L));
    private final List<Long> paidIds = new ArrayList<>();
    private final AtomicLong nanos = new AtomicLong();
    /** 페이지를 읽을 때마다 흐르는 시간 */
    private long nanosPerPage = 0L;

    private final SettlementScheduler scheduler = new SettlementScheduler(facade, POLICY) {
        @Override
        long currentNanos() {
            return nanos.get();
        }
    };

    @BeforeEach
    void setUp() {
        when(facade.findRetryTargetIds(anyLong(), anyInt())).thenAnswer(inv -> {
            long afterId = inv.getArgument(0);
            int limit = inv.getArgument(1);
            nanos.addAndGet(nanosPerPage);
            return targets.stream().filter(id -> id > afterId).limit(limit).toList();
        });
        when(facade.payout(anyLong(), any(), any())).thenAnswer(inv -> {
            paidIds.add(inv.getArgument(0));
            return paid(inv.getArgument(0));
        });
    }

    @Test
    @DisplayName("대상이 한 페이지를 넘으면 한 회차 안에서 페이지를 이어 읽어 모두 지급하고, 대상에 남은 건을 다시 집지 않는다")
    void paysAllPagesInOneRound() {
        scheduler.retryPayouts();

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), paidIds);   // 각 1번씩
        verify(facade).findRetryTargetIds(0L, 2);
        verify(facade).findRetryTargetIds(2L, 2);
        verify(facade).findRetryTargetIds(4L, 2);
        verify(facade).findRetryTargetIds(5L, 2);   // 빈 페이지로 끝
    }

    @Test
    @DisplayName("회차가 시간 한도(8분)를 넘기면 멈추고 남은 건은 다음 회차에 맡긴다")
    void stopsAtTimeLimit() {
        nanosPerPage = SettlementScheduler.PAYOUT_ROUND_TIME_LIMIT.toNanos() / 2 + 1;   // 두 페이지째 읽은 뒤 한도 초과

        scheduler.retryPayouts();

        assertEquals(List.of(1L, 2L, 3L, 4L), paidIds);
        verify(facade, never()).findRetryTargetIds(4L, 2);
    }

    @Test
    @DisplayName("반복 중 월 정산 Job이 시작되면 다음 페이지를 읽지 않고 멈춘다")
    void stopsWhenMonthlyJobStarts() {
        when(facade.isMonthlyJobRunning()).thenReturn(false, true);   // 회차 시작 때는 아님, 첫 페이지 뒤 시작됨

        scheduler.retryPayouts();

        assertEquals(List.of(1L, 2L), paidIds);
        verify(facade, never()).findRetryTargetIds(2L, 2);
    }

    @Test
    @DisplayName("회차 시작 때 월 정산 Job이 실행 중이면 지급하지 않는다")
    void skipsWhenMonthlyJobRunning() {
        when(facade.isMonthlyJobRunning()).thenReturn(true);

        scheduler.retryPayouts();

        verify(facade, never()).findRetryTargetIds(anyLong(), anyInt());
        verify(facade, never()).payout(anyLong(), any(SettlementTrigger.class), any());
    }

    private static SettlementPayoutResult paid(Long id) {
        return new SettlementPayoutResult(id, true, SettlementStatus.PAID, 1, 9_000L, 1_000L, 0, null, null, null,
                null);
    }
}
