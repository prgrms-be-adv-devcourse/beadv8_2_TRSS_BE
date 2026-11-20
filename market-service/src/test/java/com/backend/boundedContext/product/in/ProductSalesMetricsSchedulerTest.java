package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.domain.ProductBehaviorPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 판매 지표 스케줄러의 조회 구간: 첫 실행은 lookback만큼, 그 뒤는 지난 성공 시각 − 겹침부터, 실패하면 지난 성공 시각을 유지한다 */
class ProductSalesMetricsSchedulerTest {

    private static final int OVERLAP_SECONDS = 30;
    private static final int LOOKBACK_MINUTES = 1440;

    private final ProductFacade productFacade = mock(ProductFacade.class);
    private final ProductSalesMetricsScheduler scheduler = new ProductSalesMetricsScheduler(productFacade,
            new ProductBehaviorPolicy(0.4, 0.4, 0.2, 1_000, 500, 4.0, 5, 30, 60, OVERLAP_SECONDS, LOOKBACK_MINUTES));

    @Test
    @DisplayName("첫 실행은 lookback만큼 돌아보고, 실패한 실행 뒤에는 지난 성공 시각 − 겹침부터 다시 읽으며, 성공하면 그 시각으로 넘어간다")
    void windows() {
        given(productFacade.publishSalesMetricsChanges(any(), any()))
                .willReturn(1)
                .willThrow(new IllegalStateException("DB 오류"))
                .willReturn(0)
                .willReturn(0);

        scheduler.publishSalesMetricsChanges();                                              // 첫 실행: 성공
        assertThatCode(scheduler::publishSalesMetricsChanges).doesNotThrowAnyException();   // 실패: WARN 로그만
        scheduler.publishSalesMetricsChanges();                                              // 실패 구간까지 다시
        scheduler.publishSalesMetricsChanges();

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(productFacade, times(4)).publishSalesMetricsChanges(from.capture(), to.capture());
        List<LocalDateTime> froms = from.getAllValues();
        List<LocalDateTime> tos = to.getAllValues();

        assertThat(Duration.between(froms.get(0), tos.get(0))).isEqualTo(Duration.ofMinutes(LOOKBACK_MINUTES));
        assertThat(froms.get(1)).isEqualTo(tos.get(0).minusSeconds(OVERLAP_SECONDS));
        assertThat(froms.get(2)).isEqualTo(tos.get(0).minusSeconds(OVERLAP_SECONDS));   // 실패한 실행의 시각으로 넘어가지 않는다
        assertThat(froms.get(3)).isEqualTo(tos.get(2).minusSeconds(OVERLAP_SECONDS));
    }
}
