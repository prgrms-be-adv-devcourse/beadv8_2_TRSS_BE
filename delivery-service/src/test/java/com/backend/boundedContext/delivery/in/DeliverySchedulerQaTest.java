package com.backend.boundedContext.delivery.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.app.DeliveryTaskResult;
import com.backend.boundedContext.delivery.domain.DeliveryPolicy;
import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnavailableException;
import com.backend.global.outbox.OutboxRelayScheduler;

/**
 * [QA] 배송 스케줄러: 건별 예외 격리, 대상 조회 실패, 회차 반복 시 막힘(head-of-line), 스레드 풀 크기
 */
@ExtendWith(OutputCaptureExtension.class)
class DeliverySchedulerQaTest {

    private static final String TOKEN = "test-callback-token-0123456789abcdef";

    private DeliveryFacade facade;
    private DeliveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        facade = mock(DeliveryFacade.class);
        DeliveryTrackerPolicy trackerPolicy = new DeliveryTrackerPolicy("http://localhost:1/graphql", "id", "secret",
                "https://palette.test/webhooks/delivery-tracker", TOKEN, 48, 24, 15000, 5, 900, true, false);
        scheduler = new DeliveryScheduler(facade, new DeliveryPolicy(2, 50), trackerPolicy);
    }

    @Test
    @DisplayName("[QA] 상태 확인: 한 건의 예상 못 한 예외(DB 등)는 그 건만 실패, 다음 건은 처리")
    void checkTracking_unexpectedException_isolated(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L));
        given(facade.checkTracking(eq(1L), any())).willThrow(new IllegalStateException("DB"));
        given(facade.checkTracking(eq(2L), any())).willReturn(DeliveryTaskResult.DONE);

        scheduler.checkTracking();

        verify(facade).checkTracking(eq(2L), any());
        assertThat(output).contains("배송 상태 확인: 완료 1건, 진행 중 0건, 실패 1건");
    }

    @Test
    @DisplayName("[QA] 대상 조회 자체가 실패하면 예외를 밖으로 던지지 않고 ERROR 한 줄")
    void targetQueryFailure_logged(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willThrow(new IllegalStateException("DB down"));
        given(facade.countActiveWebhooks(any())).willThrow(new IllegalStateException("DB down"));
        given(facade.findWebhookRenewTargetIds(any(), anyLong(), anyInt())).willThrow(new IllegalStateException("DB down"));
        given(facade.requestSafetyCheck(any())).willThrow(new IllegalStateException("DB down"));

        assertThatCode(() -> {
            scheduler.checkTracking();
            scheduler.registerWebhooks();
            scheduler.renewWebhooks();
            scheduler.requestSafetyCheck();
        }).doesNotThrowAnyException();

        assertThat(output).contains("배송 상태 확인 대상 조회에 실패했습니다")
                .contains("웹훅 등록 대상 조회에 실패했습니다")
                .contains("웹훅 재등록 대상 조회에 실패했습니다")
                .contains("안전망 확인 실패");
    }

    @Test
    @DisplayName("[QA] 상태 확인 중 호출 실패 로그(WARN)에 콜백 토큰이 없다")
    void unavailableLog_noToken(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L));
        given(facade.checkTracking(eq(1L), any())).willThrow(new DeliveryTrackerUnavailableException("Delivery Tracker가 오류를 돌려줬습니다. HTTP 200, code=INTERNAL"));

        scheduler.checkTracking();

        assertThat(output).doesNotContain(TOKEN);
    }

    /**
     * 재현: 특정 송장 한 건만 계속 실패하는 GraphQL 오류(예: registerTrackWebhook NOT_FOUND — 결정 D8 콘솔 확인 전, 잘못 입력한 송장)는
     * 클라이언트가 Unavailable로 분류하고, 스케줄러는 회차를 멈춘다. 매 회차가 afterId=0부터 다시 시작하므로
     * 그 건보다 id가 큰 배송 건은 웹훅이 영영 등록되지 않는다.
     * 기대: 건별로 계속 실패하는 오류는 그 건만 건너뛰고 뒤 건은 등록된다.
     */
    @Test
    @DisplayName("[QA·재현] 한 건만 계속 실패(건별 GraphQL 오류)해도 3회차 안에 뒤 건의 웹훅은 등록돼야 한다")
    void registerWebhooks_poisonItem_doesNotBlockOthers() {
        given(facade.countActiveWebhooks(any())).willReturn(0L);
        given(facade.findWebhookRegisterTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L));
        given(facade.registerWebhook(eq(1L), any()))
                .willThrow(new DeliveryTrackerUnavailableException("Delivery Tracker가 오류를 돌려줬습니다. HTTP 200, code=NOT_FOUND"));
        given(facade.registerWebhook(eq(2L), any())).willReturn(DeliveryTaskResult.DONE);

        scheduler.registerWebhooks();
        scheduler.registerWebhooks();
        scheduler.registerWebhooks();

        verify(facade, atLeastOnce()).registerWebhook(eq(2L), any());
    }

    @Test
    @DisplayName("[QA] 스레드 풀(5)은 delivery 안 @Scheduled 작업 수(배송 4 + 릴레이 1) 이상")
    void schedulingPoolSize_coversJobs() throws Exception {
        long jobs = Arrays.stream(DeliveryScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class)).count()
                + Arrays.stream(OutboxRelayScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class)).count();
        String yaml = new String(DeliverySchedulerQaTest.class.getResourceAsStream("/application.yaml").readAllBytes());
        int poolSize = Integer.parseInt(yaml.replaceAll("(?s).*\\n\\s+pool:\\s*\\n\\s+size:\\s*(\\d+).*", "$1"));

        assertThat(jobs).isEqualTo(5);
        assertThat(poolSize).isGreaterThanOrEqualTo((int) jobs);
    }

    @Test
    @DisplayName("[QA] webhookEnabled=false여도 안전망은 돈다")
    void webhookDisabled_safetyStillRuns() {
        DeliveryTrackerPolicy off = new DeliveryTrackerPolicy("http://localhost:1/graphql", "id", "secret",
                "http://palette.test/webhooks/delivery-tracker", TOKEN, 48, 24, 15000, 5, 900, false, false);
        DeliveryScheduler disabled = new DeliveryScheduler(facade, new DeliveryPolicy(2, 50), off);
        given(facade.requestSafetyCheck(any())).willReturn(3);

        disabled.registerWebhooks();
        disabled.requestSafetyCheck();

        verify(facade).requestSafetyCheck(any());
        verify(facade, never()).registerWebhook(anyLong(), any());
    }
}
