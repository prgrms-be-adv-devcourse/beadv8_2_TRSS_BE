package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.domain.ProductBehaviorPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 판매 지표 스케줄러 (engine=es일 때만). 판매량은 주문 결제·취소·반품으로 바뀌는데 그 변경을 알리는 주문 이벤트가 충분하지 않고
 * (결제 이벤트는 배송지 있는 주문만, 반품 이벤트엔 상품 항목이 없음, 취소 이벤트 없음), 최근 30일 구간 밖으로 나가는 판매는 이벤트가 없다.
 * 그래서 주기마다 그 상품을 찾아 지표 변경 이벤트를 발행하고, 검색 색인 컨슈머가 DB를 다시 읽어 판매량·행동 점수를 맞춘다.
 * <p>
 * 조회 구간은 (지난 성공 실행 시각 − metricsOverlapSeconds, 지금]이다. 주문 상태 이력은 주문 트랜잭션이 커밋돼야 보이므로,
 * 겹쳐 읽어 커밋 지연으로 놓치지 않게 한다. 겹쳐서 생기는 중복 이벤트는 검색 색인 컨슈머만 받고 결과가 같다.
 * 기동 후 첫 실행은 metricsLookbackMinutes(기본 하루)만큼 돌아봐 앱이 멈춘 동안 바뀐 판매량을 채운다. 실패하면 지난 실행 시각을 그대로 둬서 다음 실행이 실패한 구간까지 읽는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSalesMetricsScheduler {
    private final ProductFacade productFacade;
    private final ProductBehaviorPolicy policy;

    /** 지난 성공 실행이 기준으로 삼은 시각. fixedDelay라 한 스레드만 읽고 쓴다 */
    private LocalDateTime lastSucceededAt;

    @Scheduled(fixedDelayString = "${custom.product.behavior.metricsIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void publishSalesMetricsChanges() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = lastSucceededAt == null
                ? now.minusMinutes(policy.getMetricsLookbackMinutes())
                : lastSucceededAt.minusSeconds(policy.getMetricsOverlapSeconds());
        try {
            int count = productFacade.publishSalesMetricsChanges(from, now);
            lastSucceededAt = now;
            if (count > 0) {
                log.debug("판매 지표: 지표 변경 이벤트 {}개 발행", count);
            }
        } catch (Exception e) {
            log.warn("판매 지표 변경 이벤트 발행에 실패했습니다. 다음 실행에서 이번 구간까지 다시 합니다.", e);
        }
    }
}
