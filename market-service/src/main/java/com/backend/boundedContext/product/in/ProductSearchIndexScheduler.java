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
 * 지난 성공 실행 뒤로 시작했거나 끝난 할인의 상품 변경 이벤트를 발행해 가격순·특가(effectivePrice, discountRate)를 맞춘다.
 * 직접 색인하지 않으므로, 검색 색인 컨슈머와 같은 상품을 동시에 써서 옛 값이 나중에 저장되는 일이 없다.
 * <p>
 * 조회 구간은 (지난 성공 실행 시각, 지금]이다. 구간을 겹치지 않게 이어 붙여 같은 경계의 이벤트를 한 번만 발행하고,
 * 실패하면 지난 실행 시각을 그대로 둬서 다음 실행이 실패한 구간까지 읽는다 (실행이 늦어져도 경계를 놓치지 않는다).
 * 지난 실행 시각은 메모리에만 두므로 기동 후 첫 실행은 discountBoundaryLookbackMinutes만큼 돌아봐 재시작 동안의 경계를 채운다.
 * 같은 상품의 이벤트를 여러 번 발행해도 색인 결과가 같으므로 인스턴스가 여러 대여도 분산 락이 필요 없다.
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
            int count = productFacade.publishDiscountBoundaryChanges(lastSucceededAt, now);
            lastSucceededAt = now;
            if (count > 0) {
                log.debug("할인 경계: 상품 변경 이벤트 {}개 발행", count);
            }
        } catch (Exception e) {
            log.warn("할인 경계 상품 변경 이벤트 발행에 실패했습니다. 다음 실행에서 이번 구간까지 다시 합니다.", e);
        }
    }
}
