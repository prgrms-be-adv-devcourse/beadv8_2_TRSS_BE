package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;

class PaymentExpireTopupsUseCaseTest {

    private final TopupRepository topupRepository = mock(TopupRepository.class);
    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 1L);
    private final PaymentExpireTopupsUseCase useCase = new PaymentExpireTopupsUseCase(paymentPolicy, topupRepository);

    @Test
    @DisplayName("지금보다 만료 시간(15분) 이전에 만든 READY 건을 FAILED_EXPIRED로 바꾸고 바뀐 건수를 돌려준다")
    void expireReadyTopups() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 10, 0);
        given(topupRepository.expireReadyTopups(eq(TopupStatus.READY), eq(TopupStatus.FAILED_EXPIRED), anyString(),
                eq(LocalDateTime.of(2026, 10, 14, 9, 45)), eq(now))).willReturn(3);

        int expired = useCase.expireReadyTopups(now);

        assertThat(expired).isEqualTo(3);
    }
}
