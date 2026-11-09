package com.backend.boundedContext.delivery.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.app.DeliveryTaskResult;
import com.backend.boundedContext.delivery.domain.DeliveryPolicy;
import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryTrackerQuotaExceededException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerServiceDownException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnauthenticatedException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnavailableException;

/**
 * 배송 스케줄러: 회차 중단·활성 웹훅 한도·요약 로그. Facade는 목으로 둔다
 */
@ExtendWith(OutputCaptureExtension.class)
class DeliverySchedulerTest {

    private static final String TOKEN = "test-callback-token-0123456789abcdef";

    private DeliveryFacade facade;

    @BeforeEach
    void setUp() {
        facade = mock(DeliveryFacade.class);
    }

    private DeliveryScheduler scheduler(int maxActiveWebhooks) {
        return scheduler(maxActiveWebhooks, true);
    }

    private DeliveryScheduler scheduler(int maxActiveWebhooks, boolean webhookEnabled) {
        DeliveryTrackerPolicy trackerPolicy = new DeliveryTrackerPolicy("http://localhost:1/graphql", "id", "secret",
                "http://localhost:8085/webhooks/delivery-tracker", TOKEN, 48, 24, 15000, 5, maxActiveWebhooks,
                webhookEnabled, true);
        return new DeliveryScheduler(facade, new DeliveryPolicy(2, 50), trackerPolicy);
    }

    @Test
    @DisplayName("상태 확인: 결과별로 세어 요약 INFO를 남긴다")
    void checkTracking_summary(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L, 3L));
        given(facade.checkTracking(eq(1L), any())).willReturn(DeliveryTaskResult.DONE);
        given(facade.checkTracking(eq(2L), any())).willReturn(DeliveryTaskResult.IN_PROGRESS);
        given(facade.checkTracking(eq(3L), any())).willThrow(new IllegalStateException("DB"));

        scheduler(900).checkTracking();

        assertThat(output).contains("배송 상태 확인: 완료 1건, 진행 중 1건, 실패 1건");
        assertThat(output).contains("배송 상태 확인 실패 deliveryId=3. 다음 실행에서 다시 처리합니다.");
    }

    @Test
    @DisplayName("인증 실패 → 회차 중단(남은 건 조회 안 함), ERROR 로그에 DeliveryTrackerUnauthenticatedException")
    void checkTracking_unauthenticated_stops(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L, 3L));
        given(facade.checkTracking(eq(1L), any())).willReturn(DeliveryTaskResult.IN_PROGRESS);
        given(facade.checkTracking(eq(2L), any())).willThrow(new DeliveryTrackerUnauthenticatedException("만료"));

        scheduler(900).checkTracking();

        verify(facade, never()).checkTracking(eq(3L), any());
        assertThat(output).contains("ERROR")
                .contains("Delivery Tracker 인증에 실패했습니다. 키가 만료됐을 수 있습니다(무료 플랜 21일). 이번 회차를 멈춥니다: job=배송 상태 확인")
                .contains("DeliveryTrackerUnauthenticatedException");
    }

    @Test
    @DisplayName("할당량 초과 → 회차 중단 + WARN")
    void checkTracking_quota_stops(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L));
        given(facade.checkTracking(eq(1L), any())).willThrow(new DeliveryTrackerQuotaExceededException("429"));

        scheduler(900).checkTracking();

        verify(facade, never()).checkTracking(eq(2L), any());
        assertThat(output).contains("WARN")
                .contains("Delivery Tracker 할당량을 넘었습니다. 이번 회차를 멈추고 다음 회차에 이어 합니다: job=배송 상태 확인");
    }

    @Test
    @DisplayName("서비스 장애(접속 실패·시간 초과·5xx) → 회차 중단 + WARN (ERROR 아님)")
    void checkTracking_unavailable_stops(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L));
        given(facade.checkTracking(eq(1L), any())).willThrow(new DeliveryTrackerServiceDownException("timeout"));

        scheduler(900).checkTracking();

        verify(facade, never()).checkTracking(eq(2L), any());
        assertThat(output).contains("Delivery Tracker 호출에 실패했습니다. 다음 회차에 다시 합니다: job=배송 상태 확인, deliveryId=1, reason=timeout")
                .doesNotContain("ERROR");
    }

    @Test
    @DisplayName("배치 크기만큼 차면 다음 배치를 마지막 id 뒤부터 조회한다")
    void checkTracking_paging() {
        List<Long> firstBatch = java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList();
        given(facade.findTrackingCheckTargetIds(0L, 50)).willReturn(firstBatch);
        given(facade.findTrackingCheckTargetIds(50L, 50)).willReturn(List.of(51L));
        given(facade.checkTracking(anyLong(), any())).willReturn(DeliveryTaskResult.IN_PROGRESS);

        scheduler(900).checkTracking();

        verify(facade, times(51)).checkTracking(anyLong(), any());
    }

    @Test
    @DisplayName("활성 웹훅 수 ≥ 한도 → 새 등록을 건너뛰고 WARN")
    void registerWebhooks_limitReached(CapturedOutput output) {
        given(facade.countActiveWebhooks(any())).willReturn(900L);

        scheduler(900).registerWebhooks();

        verify(facade, never()).findWebhookRegisterTargetIds(anyLong(), anyInt());
        verify(facade, never()).registerWebhook(anyLong(), any());
        assertThat(output).contains("활성 웹훅이 한도에 닿아 새 등록을 미룹니다: active=900, max=900");
    }

    @Test
    @DisplayName("남은 자리만큼만 새로 등록한다 (한도 900, 활성 898 → 2건)")
    void registerWebhooks_onlyRemaining(CapturedOutput output) {
        given(facade.countActiveWebhooks(any())).willReturn(898L);
        given(facade.findWebhookRegisterTargetIds(0L, 2)).willReturn(List.of(1L, 2L));
        given(facade.findWebhookRegisterTargetIds(0L, 1)).willReturn(List.of(1L));
        given(facade.registerWebhook(anyLong(), any())).willReturn(DeliveryTaskResult.DONE);

        scheduler(900).registerWebhooks();

        verify(facade, times(2)).registerWebhook(anyLong(), any());
        assertThat(output).contains("웹훅 등록: 등록 2건, 건너뜀 0건, 실패 0건")
                .contains("활성 웹훅이 한도에 닿아 새 등록을 미룹니다");
    }

    @Test
    @DisplayName("한도에 닿아도 재등록은 한다")
    void renewWebhooks_ignoresLimit() {
        given(facade.countActiveWebhooks(any())).willReturn(900L);
        given(facade.findWebhookRenewTargetIds(any(), anyLong(), anyInt())).willReturn(List.of(7L));
        given(facade.registerWebhook(eq(7L), any())).willReturn(DeliveryTaskResult.DONE);

        scheduler(900).renewWebhooks();

        verify(facade).registerWebhook(eq(7L), any());
    }

    @Test
    @DisplayName("재등록 기준 = 지금 + webhookRenewBeforeHours(24)")
    void renewWebhooks_threshold() {
        given(facade.findWebhookRenewTargetIds(any(), anyLong(), anyInt())).willReturn(List.of());
        LocalDateTime before = LocalDateTime.now().plusHours(24);

        scheduler(900).renewWebhooks();

        org.mockito.ArgumentCaptor<LocalDateTime> threshold = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(facade).findWebhookRenewTargetIds(threshold.capture(), eq(0L), eq(50));
        assertThat(threshold.getValue()).isAfterOrEqualTo(before).isBefore(before.plusSeconds(5));
    }

    @Test
    @DisplayName("웹훅 등록 중 인증 실패 → 회차 중단 + ERROR")
    void registerWebhooks_unauthenticated(CapturedOutput output) {
        given(facade.countActiveWebhooks(any())).willReturn(0L);
        given(facade.findWebhookRegisterTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L));
        given(facade.registerWebhook(eq(1L), any())).willThrow(new DeliveryTrackerUnauthenticatedException("만료"));

        scheduler(900).registerWebhooks();

        verify(facade, never()).registerWebhook(eq(2L), any());
        assertThat(output).contains("이번 회차를 멈춥니다: job=웹훅 등록");
    }

    @Test
    @DisplayName("webhookEnabled=false → 웹훅 등록·재등록을 하지 않고 INFO는 한 번만. 상태 확인은 그대로 돈다")
    void webhookDisabled_skipsRegisterAndRenew(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L));
        given(facade.checkTracking(eq(1L), any())).willReturn(DeliveryTaskResult.DONE);
        DeliveryScheduler scheduler = scheduler(900, false);

        scheduler.registerWebhooks();
        scheduler.renewWebhooks();
        scheduler.registerWebhooks();
        scheduler.checkTracking();

        verify(facade, never()).countActiveWebhooks(any());
        verify(facade, never()).findWebhookRegisterTargetIds(anyLong(), anyInt());
        verify(facade, never()).findWebhookRenewTargetIds(any(), anyLong(), anyInt());
        verify(facade, never()).registerWebhook(anyLong(), any());
        verify(facade).checkTracking(eq(1L), any());
        String message = "웹훅이 꺼져 있어(custom.deliveryTracker.webhookEnabled=false) 웹훅 등록·재등록을 하지 않습니다";
        assertThat(output.getOut().split(java.util.regex.Pattern.quote(message), -1)).hasSize(2);
        assertThat(output).contains("배송 상태 확인: 완료 1건");
    }

    @Test
    @DisplayName("웹훅 등록이 SKIPPED(BAD_REQUEST 등)여도 회차를 멈추지 않고 다음 건을 등록한다")
    void registerWebhooks_skippedContinues(CapturedOutput output) {
        given(facade.countActiveWebhooks(any())).willReturn(0L);
        given(facade.findWebhookRegisterTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L));
        given(facade.registerWebhook(eq(1L), any())).willReturn(DeliveryTaskResult.SKIPPED);
        given(facade.registerWebhook(eq(2L), any())).willReturn(DeliveryTaskResult.DONE);

        scheduler(900).registerWebhooks();

        verify(facade).registerWebhook(eq(2L), any());
        assertThat(output).contains("웹훅 등록: 등록 1건, 건너뜀 1건, 실패 0건");
    }

    @Test
    @DisplayName("건별 GraphQL 오류(Unavailable) → 그 건만 FAILED + WARN, 다음 건은 처리한다 (상태 확인·웹훅 등록 모두)")
    void itemError_continues(CapturedOutput output) {
        given(facade.findTrackingCheckTargetIds(anyLong(), anyInt())).willReturn(List.of(1L, 2L));
        given(facade.checkTracking(eq(1L), any())).willThrow(new DeliveryTrackerUnavailableException("Delivery Tracker가 오류를 돌려줬습니다. HTTP 200, code=INTERNAL"));
        given(facade.checkTracking(eq(2L), any())).willReturn(DeliveryTaskResult.DONE);
        given(facade.countActiveWebhooks(any())).willReturn(0L);
        given(facade.findWebhookRegisterTargetIds(anyLong(), anyInt())).willReturn(List.of(3L, 4L));
        given(facade.registerWebhook(eq(3L), any())).willThrow(new DeliveryTrackerUnavailableException("Delivery Tracker가 오류를 돌려줬습니다. HTTP 200, code=NOT_FOUND"));
        given(facade.registerWebhook(eq(4L), any())).willReturn(DeliveryTaskResult.DONE);

        DeliveryScheduler scheduler = scheduler(900);
        scheduler.checkTracking();
        scheduler.registerWebhooks();

        verify(facade).checkTracking(eq(2L), any());
        verify(facade).registerWebhook(eq(4L), any());
        assertThat(output).contains("Delivery Tracker가 이 건을 처리하지 못했습니다. 이 건만 건너뜁니다: job=배송 상태 확인, deliveryId=1")
                .contains("이 건만 건너뜁니다: job=웹훅 등록, deliveryId=3, reason=Delivery Tracker가 오류를 돌려줬습니다. HTTP 200, code=NOT_FOUND")
                .contains("배송 상태 확인: 완료 1건, 진행 중 0건, 실패 1건")
                .contains("웹훅 등록: 등록 1건, 건너뜀 0건, 실패 1건")
                .doesNotContain("ERROR");
    }

    @Test
    @DisplayName("회차 끝 남은 대상 조회가 실패해도 예외가 밖으로 나가지 않고 고정 문구 ERROR")
    void registerWebhooks_finalLookupFailure_caught(CapturedOutput output) {
        given(facade.countActiveWebhooks(any())).willReturn(899L);
        given(facade.findWebhookRegisterTargetIds(0L, 1))
                .willReturn(List.of(1L))
                .willThrow(new IllegalStateException("db down"));
        given(facade.registerWebhook(eq(1L), any())).willReturn(DeliveryTaskResult.DONE);

        scheduler(900).registerWebhooks();

        assertThat(output).contains("웹훅 등록 대상 조회에 실패했습니다. 다음 실행에서 다시 처리합니다.");
    }
}
