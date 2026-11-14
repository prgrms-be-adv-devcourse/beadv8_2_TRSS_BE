package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.TopupStuckCount;

class PaymentWarnStuckTopupsUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 10, 0);
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 14, 9, 0);
    private static final EnumSet<TopupStatus> UNSETTLED = EnumSet.of(TopupStatus.PROCESSING, TopupStatus.CANCELING, TopupStatus.METHOD_CANCELING);

    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 60, 1L);
    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentWarnStuckTopupsUseCase useCase = new PaymentWarnStuckTopupsUseCase(paymentPolicy, topupRepository);

    @Test
    @DisplayName("결과가 정해지지 않은 상태로 1시간 넘게 남은 충전 건을 상태별로 세어 전체 건수를 반환한다")
    void warn() {
        given(topupRepository.countStale(UNSETTLED, CUTOFF)).willReturn(List.of(
                new TopupStuckCount(TopupStatus.PROCESSING, 3, 11L),
                new TopupStuckCount(TopupStatus.CANCELING, 1, 20L)));

        assertThat(useCase.warnStuckTopups(NOW)).isEqualTo(4);
    }

    @Test
    @DisplayName("오래 남은 충전 건이 없으면 0을 반환한다")
    void none() {
        given(topupRepository.countStale(UNSETTLED, CUTOFF)).willReturn(List.of());

        assertThat(useCase.warnStuckTopups(NOW)).isZero();
    }
}
