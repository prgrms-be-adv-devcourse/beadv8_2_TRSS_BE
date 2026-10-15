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

/**
 * 주문 스케줄러
 * 예외는 여기서 잡아 로그로 남기고 다음 실행에서 다시 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderScheduler {

    private final OrderFacade orderFacade;
    private final OrderPolicy orderPolicy;

    /**
     * 결제 후 7일 자동 구매 확정: 이전 실행이 끝난 뒤 autoConfirmIntervalSeconds초마다 실행
     * 대상을 autoConfirmBatchSize건씩 id 순으로 끝까지 조회하고, 한 건씩 별도 트랜잭션으로 확정한다.
     * 실패한 주문은 PAID로 남아 다음 실행에서 다시 대상이 된다.
     */
    @Scheduled(fixedDelayString = "${custom.order.autoConfirmIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void autoConfirmOrders() {
        LocalDateTime now = LocalDateTime.now();
        int batchSize = orderPolicy.getAutoConfirmBatchSize();
        long afterId = 0L;
        int confirmed = 0;
        int skipped = 0;
        int failed = 0;

        try {
            while (true) {
                List<Long> orderIds = orderFacade.findAutoConfirmTargetIds(now, afterId, batchSize);
                for (Long orderId : orderIds) {
                    try {
                        if (orderFacade.autoConfirmOrder(orderId, now)) {
                            confirmed++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.error("자동 구매 확정 실패 orderId={}. 다음 실행에서 다시 처리합니다.", orderId, e);
                    }
                }
                if (orderIds.size() < batchSize) {
                    break;
                }
                afterId = orderIds.get(orderIds.size() - 1);
            }
        } catch (Exception e) {
            log.error("자동 구매 확정 대상 조회에 실패했습니다. 다음 실행에서 다시 처리합니다.", e);
        }

        if (confirmed + skipped + failed > 0) {
            log.info("자동 구매 확정: 확정 {}건, 건너뜀 {}건, 실패 {}건", confirmed, skipped, failed);
        }
    }
}
