package com.backend.boundedContext.delivery.in;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.app.DeliveryTaskResult;
import com.backend.boundedContext.delivery.domain.DeliveryPolicy;
import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryTrackerQuotaExceededException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerServiceDownException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnauthenticatedException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배송 스케줄러 (Delivery Tracker 연동)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryScheduler {

    static final String JOB_TRACKING_CHECK = "배송 상태 확인";
    static final String JOB_WEBHOOK_REGISTER = "웹훅 등록";
    static final String JOB_WEBHOOK_RENEW = "웹훅 재등록";
    static final String JOB_SAFETY_CHECK = "안전망 확인";

    private final DeliveryFacade deliveryFacade;
    private final DeliveryPolicy deliveryPolicy;
    private final DeliveryTrackerPolicy deliveryTrackerPolicy;

    // 웹훅이 꺼져 있다는 INFO를 한 번만 남기기 위한 표시
    private final AtomicBoolean webhookDisabledLogged = new AtomicBoolean(false);

    // 확인 요청이 있는 SHIPPED를 조회해 완료를 반영한다
    @Scheduled(fixedDelayString = "${custom.delivery.trackingCheckIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void checkTracking() {
        Map<DeliveryTaskResult, Integer> counts = processInBatches(JOB_TRACKING_CHECK,
                (afterId, limit) -> deliveryFacade.findTrackingCheckTargetIds(afterId, limit),
                // 조회 직전 시각. 이 뒤에 온 콜백의 요청은 지우지 않는다
                deliveryId -> deliveryFacade.checkTracking(deliveryId, LocalDateTime.now()));
        int done = counts.get(DeliveryTaskResult.DONE);
        int inProgress = counts.get(DeliveryTaskResult.IN_PROGRESS);
        int failed = counts.get(DeliveryTaskResult.FAILED);
        if (done + inProgress + failed > 0) {
            log.info("배송 상태 확인: 완료 {}건, 진행 중 {}건, 실패 {}건", done, inProgress, failed);
        }
    }

    // 웹훅 미등록(새 송장·실패) 건 등록. 활성 웹훅 수가 한도에 닿으면 새 등록만 미룬다(안전망이 대신 확인)
    @Scheduled(fixedDelayString = "${custom.delivery.webhookRegisterIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void registerWebhooks() {
        if (webhookDisabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int max = deliveryTrackerPolicy.getMaxActiveWebhooks();
        long active;
        try {
            active = deliveryFacade.countActiveWebhooks(now);
        } catch (Exception e) {
            log.error("{} 대상 조회에 실패했습니다. 다음 실행에서 다시 처리합니다.", JOB_WEBHOOK_REGISTER, e);
            return;
        }
        long[] remaining = {max - active};
        if (remaining[0] <= 0) {
            warnWebhookLimit(active, max);
            return;
        }

        Map<DeliveryTaskResult, Integer> counts = processInBatches(JOB_WEBHOOK_REGISTER,
                (afterId, limit) -> remaining[0] <= 0 ? List.of()
                        : deliveryFacade.findWebhookRegisterTargetIds(afterId, (int) Math.min(limit, remaining[0])),
                deliveryId -> {
                    DeliveryTaskResult result = deliveryFacade.registerWebhook(deliveryId, now);
                    if (result == DeliveryTaskResult.DONE) remaining[0]--;
                    return result;
                });
        logWebhookSummary(JOB_WEBHOOK_REGISTER, counts);
        try {
            if (remaining[0] <= 0 && !deliveryFacade.findWebhookRegisterTargetIds(0L, 1).isEmpty()) {
                warnWebhookLimit(max, max);
            }
        } catch (Exception e) {
            log.error("{} 대상 조회에 실패했습니다. 다음 실행에서 다시 처리합니다.", JOB_WEBHOOK_REGISTER, e);
        }
    }

    // 만료가 webhookRenewBeforeHours 안으로 다가온 웹훅 재등록. 한도와 무관하게 한다(이미 세고 있는 웹훅)
    @Scheduled(fixedDelayString = "${custom.delivery.webhookRenewIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void renewWebhooks() {
        if (webhookDisabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusHours(deliveryTrackerPolicy.getWebhookRenewBeforeHours());
        Map<DeliveryTaskResult, Integer> counts = processInBatches(JOB_WEBHOOK_RENEW,
                (afterId, limit) -> deliveryFacade.findWebhookRenewTargetIds(threshold, afterId, limit),
                deliveryId -> deliveryFacade.registerWebhook(deliveryId, now));
        logWebhookSummary(JOB_WEBHOOK_RENEW, counts);
    }

    // 발송 후 safetyCheckAfterDays일 지나도 SHIPPED인 건에 확인 요청을 남긴다(다음 상태 확인에서 조회)
    @Scheduled(cron = "${custom.delivery.safetyCheckCron}", zone = "Asia/Seoul")
    public void requestSafetyCheck() {
        try {
            int requested = deliveryFacade.requestSafetyCheck(LocalDateTime.now());
            if (requested > 0) {
                log.info("{}: 확인 요청 {}건", JOB_SAFETY_CHECK, requested);
            }
        } catch (Exception e) {
            log.error("{} 실패. 다음 실행에서 다시 처리합니다.", JOB_SAFETY_CHECK, e);
        }
    }

    /**
     * 대상을 schedulerBatchSize건씩 id 순으로 조회하고 한 건씩 처리한다.
     * Delivery Tracker 예외가 나면 그 회차를 멈춘다. 그 밖의 예외는 그 건만 실패로 세고 다음 건으로 넘어간다.
     */
    private Map<DeliveryTaskResult, Integer> processInBatches(String jobName,
                                                               BiFunction<Long, Integer, List<Long>> findTargets,
                                                               Function<Long, DeliveryTaskResult> process) {
        Map<DeliveryTaskResult, Integer> counts = new EnumMap<>(DeliveryTaskResult.class);
        for (DeliveryTaskResult result : DeliveryTaskResult.values()) counts.put(result, 0);
        int batchSize = deliveryPolicy.getSchedulerBatchSize();
        long afterId = 0L;

        try {
            while (true) {
                List<Long> deliveryIds = findTargets.apply(afterId, batchSize);
                for (Long deliveryId : deliveryIds) {
                    try {
                        counts.merge(process.apply(deliveryId), 1, Integer::sum);
                    } catch (DeliveryTrackerUnauthenticatedException e) {
                        counts.merge(DeliveryTaskResult.FAILED, 1, Integer::sum);
                        log.error("Delivery Tracker 인증에 실패했습니다. 키가 만료됐을 수 있습니다(무료 플랜 21일). 이번 회차를 멈춥니다: job={}",
                                jobName, e);
                        return counts;
                    } catch (DeliveryTrackerQuotaExceededException e) {
                        counts.merge(DeliveryTaskResult.FAILED, 1, Integer::sum);
                        log.warn("Delivery Tracker 할당량을 넘었습니다. 이번 회차를 멈추고 다음 회차에 이어 합니다: job={}", jobName);
                        return counts;
                    } catch (DeliveryTrackerServiceDownException e) {
                        counts.merge(DeliveryTaskResult.FAILED, 1, Integer::sum);
                        log.warn("Delivery Tracker 호출에 실패했습니다. 다음 회차에 다시 합니다: job={}, deliveryId={}, reason={}",
                                jobName, deliveryId, e.getMessage());
                        return counts;
                    } catch (DeliveryTrackerUnavailableException e) {
                        // 건별 오류: 이 건만 실패로 세고 다음 건으로 넘어간다. 대상 컬럼이 남아 다음 회차에 다시 시도된다
                        counts.merge(DeliveryTaskResult.FAILED, 1, Integer::sum);
                        log.warn("Delivery Tracker가 이 건을 처리하지 못했습니다. 이 건만 건너뜁니다: job={}, deliveryId={}, reason={}",
                                jobName, deliveryId, e.getMessage());
                    } catch (Exception e) {
                        counts.merge(DeliveryTaskResult.FAILED, 1, Integer::sum);
                        log.error("{} 실패 deliveryId={}. 다음 실행에서 다시 처리합니다.", jobName, deliveryId, e);
                    }
                }
                if (deliveryIds.size() < batchSize) {
                    break;
                }
                afterId = deliveryIds.get(deliveryIds.size() - 1);
            }
        } catch (Exception e) {
            log.error("{} 대상 조회에 실패했습니다. 다음 실행에서 다시 처리합니다.", jobName, e);
        }
        return counts;
    }

    /** 웹훅이 꺼져 있으면 true. 처음 한 번만 INFO를 남긴다 */
    private boolean webhookDisabled() {
        if (deliveryTrackerPolicy.isWebhookEnabled()) {
            return false;
        }
        if (webhookDisabledLogged.compareAndSet(false, true)) {
            log.info("웹훅이 꺼져 있어(custom.deliveryTracker.webhookEnabled=false) 웹훅 등록·재등록을 하지 않습니다. "
                    + "배송 완료는 안전망 확인(발송 {}일 뒤 매일)으로 반영됩니다.", deliveryPolicy.getSafetyCheckAfterDays());
        }
        return true;
    }

    private void logWebhookSummary(String jobName, Map<DeliveryTaskResult, Integer> counts) {
        int done = counts.get(DeliveryTaskResult.DONE);
        int skipped = counts.get(DeliveryTaskResult.SKIPPED);
        int failed = counts.get(DeliveryTaskResult.FAILED);
        if (done + skipped + failed > 0) {
            log.info("{}: 등록 {}건, 건너뜀 {}건, 실패 {}건", jobName, done, skipped, failed);
        }
    }

    private static void warnWebhookLimit(long active, int max) {
        log.warn("활성 웹훅이 한도에 닿아 새 등록을 미룹니다: active={}, max={}", active, max);
    }
}
