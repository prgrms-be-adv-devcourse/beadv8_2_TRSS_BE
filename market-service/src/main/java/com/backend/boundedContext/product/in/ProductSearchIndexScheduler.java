package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 할인 경계 재색인 (engine=es일 때만). 할인은 시각이 되면 시작·종료되는데 그때 상품을 바꾸는 요청이 없으므로,
 * 지난 성공 실행 뒤로 시작했거나 끝난 할인의 상품을 다시 색인해 가격순(effectivePrice)을 맞춘다.
 * <p>
 * 조회 구간은 (지난 성공 실행 시각 − discountBoundaryOverlapSeconds, 지금]이다. 조금씩 겹쳐 읽어서 실행 지연·커밋 지연을 덮고,
 * 실패하면 지난 실행 시각을 그대로 둬서 다음 실행이 실패한 구간까지 읽는다.
 * 지난 실행 시각은 메모리에만 두므로 기동 후 첫 실행은 discountBoundaryLookbackMinutes만큼 돌아봐 재시작 동안의 경계를 채운다.
 * 같은 상품을 여러 번 색인해도 결과가 같으므로 인스턴스가 여러 대여도 분산 락이 필요 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexScheduler {
    private final ProductFacade productFacade;

    /** 지난 성공 실행이 기준으로 삼은 시각. fixedDelay라 같은 스케줄러가 겹쳐 돌지 않아 한 스레드만 읽고 쓴다 */
    private LocalDateTime lastSucceededAt;

    @Scheduled(fixedDelayString = "${custom.product.search.discountBoundaryIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void reindexDiscountBoundaries() {
        LocalDateTime now = LocalDateTime.now();
        try {
            int count = productFacade.reindexDiscountBoundaries(lastSucceededAt, now);
            lastSucceededAt = now;
            if (count > 0) {
                log.debug("할인 경계 재색인: 상품 {}개", count);
            }
        } catch (Exception e) {
            log.warn("할인 경계 재색인에 실패했습니다. 다음 실행에서 이번 구간까지 다시 합니다.", e);
        }
    }
}
