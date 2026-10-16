package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.OrderPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 주문 스케줄러
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderScheduler {

    private final OrderFacade orderFacade;
    private final OrderPolicy orderPolicy;

    // 결제 후 7일 자동 구매 확정
    @Scheduled(fixedDelayString = "${custom.order.autoConfirmIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void autoConfirmOrders() {
        LocalDateTime now = LocalDateTime.now();
        processInBatches("자동 구매 확정", "확정",
                (afterId, limit) -> orderFacade.findAutoConfirmTargetIds(now, afterId, limit),
                orderId -> orderFacade.autoConfirmOrder(orderId, now));
    }

    // 결제 만료 취소
    @Scheduled(fixedDelayString = "${custom.order.expireIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void expireOrders() {
        LocalDateTime now = LocalDateTime.now();
        processInBatches("결제 만료 취소", "취소",
                (afterId, limit) -> orderFacade.findExpireTargetIds(now, afterId, limit),
                orderId -> orderFacade.expireOrder(orderId, now));
    }

    // 대상을 schedulerBatchSize건씩 id 순으로 조회하고, 한 건씩 처리한다.
    private void processInBatches(String jobName, String doneLabel,
                                  BiFunction<Long, Integer, List<Long>> findTargets,
                                  Predicate<Long> process) {
        int batchSize = orderPolicy.getSchedulerBatchSize();
        long afterId = 0L;
        int done = 0;
        int skipped = 0;
        int failed = 0;

        try {
            while (true) {
                List<Long> orderIds = findTargets.apply(afterId, batchSize);
                for (Long orderId : orderIds) {
                    try {
                        if (process.test(orderId)) {
                            done++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.error("{} 실패 orderId={}. 다음 실행에서 다시 처리합니다.", jobName, orderId, e);
                    }
                }
                if (orderIds.size() < batchSize) {
                    break;
                }
                afterId = orderIds.get(orderIds.size() - 1);
            }
        } catch (Exception e) {
            log.error("{} 대상 조회에 실패했습니다. 다음 실행에서 다시 처리합니다.", jobName, e);
        }

        if (done + skipped + failed > 0) {
            log.info("{}: {} {}건, 건너뜀 {}건, 실패 {}건", jobName, doneLabel, done, skipped, failed);
        }
    }
}
