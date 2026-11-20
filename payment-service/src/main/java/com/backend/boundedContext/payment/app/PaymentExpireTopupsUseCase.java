package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentExpireTopupsUseCase {

    private static final String EXPIRED_REASON = "결제창 진행 시간이 지나 충전이 만료되었습니다.";

    private final PaymentPolicy paymentPolicy;
    private final TopupRepository topupRepository;

    // READY 만료: 만료 시간(topupReadyTtl)이 지난 READY 충전 건을 FAILED_EXPIRED로 정리하고 정리한 건수를 반환
    public int expireReadyTopups(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(paymentPolicy.topupReadyTtl());
        return topupRepository.expireReadyTopups(TopupStatus.READY, TopupStatus.FAILED_EXPIRED, EXPIRED_REASON, cutoff, now);
    }
}
