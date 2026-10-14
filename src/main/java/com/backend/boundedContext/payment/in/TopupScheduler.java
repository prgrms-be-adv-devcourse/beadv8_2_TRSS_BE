package com.backend.boundedContext.payment.in;

import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 충전 스케줄러. @EnableScheduling(공통 SchedulingConfig)이 있어야 실행됨
 * HTTP 요청이 아니라 GlobalExceptionHandler가 동작하지 않으므로, 예외는 여기서 잡아 로그로 남기고 다음 실행에서 다시 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopupScheduler {

    private final PaymentFacade paymentFacade;

    // READY 만료: 이전 실행이 끝난 뒤 topupExpireIntervalSeconds초마다 실행
    @Scheduled(fixedDelayString = "${custom.payment.topupExpireIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void expireReadyTopups() {
        try {
            int expired = paymentFacade.expireReadyTopups();
            if (expired > 0) {
                log.info("READY 충전 건 {}건을 만료(FAILED_EXPIRED)로 정리했습니다.", expired);
            }
        } catch (Exception e) {
            log.error("READY 충전 건 만료 처리에 실패했습니다. 다음 실행에서 다시 처리합니다.", e);
        }
    }

    // PROCESSING 결과 확인: 이전 실행이 끝난 뒤 topupReconcileIntervalSeconds초마다 실행
    @Scheduled(fixedDelayString = "${custom.payment.topupReconcileIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void reconcileProcessingTopups() {
        try {
            TopupReconcileResult result = paymentFacade.reconcileProcessingTopups();
            if (result.total() > 0) {
                log.info("PROCESSING 충전 건 결과 확인: 반영 {}건, 실패 {}건, 확인 대기 {}건, 오류 {}건",
                        result.completed(), result.failed(), result.pending(), result.errors());
            }
        } catch (Exception e) {
            log.error("PROCESSING 충전 건 결과 확인에 실패했습니다. 다음 실행에서 다시 처리합니다.", e);
        }
    }
}
