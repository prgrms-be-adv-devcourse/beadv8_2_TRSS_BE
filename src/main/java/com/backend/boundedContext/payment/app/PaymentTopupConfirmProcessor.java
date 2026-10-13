package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 충전 승인의 DB 트랜잭션 구간
 * 전체 승인 흐름은 Facade에서 조율하되, Facade 전체를 하나의 트랜잭션으로 묶지는 않음
 * Tx1 → 토스 호출(트랜잭션 밖) → Tx2 중 DB 작업만 각각 짧은 트랜잭션으로 실행하기 위해 별도 빈으로 분리
 * (같은 클래스 내부 호출은 Spring 프록시를 거치지 않아 메서드의 @Transactional이 적용되지 않음)
 */
@Component
@RequiredArgsConstructor
public class PaymentTopupConfirmProcessor {

    private final TopupRepository topupRepository;

    /**
     * Tx1: 토스 승인 호출 전 충전 건을 PROCESSING으로 선점
     * 1. 충전 건 행 잠금 조회 (조건에 memberId 포함, 남의 충전 건이면 404)
     * 2. READY가 아니면 409 TOPUP_ALREADY_PROCESSED (상태는 그대로)
     * 3. 이미 다른 충전 건에 쓰인 결제키면 409 TOPUP_ALREADY_PROCESSED
     * 4. 저장 금액 ≠ 요청 금액이면 FAILED_AMOUNT_MISMATCH로 기록하고 결과로 반환 (예외를 던지지 않아 기록이 커밋됨)
     * 5. READY → PROCESSING, 결제키 저장
     */
    @Transactional
    public TopupConfirmStartResult start(Long memberId, String orderId, String paymentKey, long amount) {
        Topup topup = topupRepository.findByTossOrderIdAndMemberIdForUpdate(orderId, memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));

        if (topup.getStatus() != TopupStatus.READY || topupRepository.existsByPaymentKey(paymentKey)) {
            throw new DomainException(PaymentErrorCode.TOPUP_ALREADY_PROCESSED);
        }

        if (topup.getAmount() != amount) {
            topup.fail(TopupStatus.FAILED_AMOUNT_MISMATCH, "충전 준비 금액과 승인 요청 금액이 다릅니다. (준비 " + topup.getAmount() + "원, 요청 " + amount + "원)");
            return new TopupConfirmStartResult(topup.getId(), TopupConfirmStartResult.Outcome.AMOUNT_MISMATCH);
        }

        topup.markProcessing(paymentKey);
        return new TopupConfirmStartResult(topup.getId(), TopupConfirmStartResult.Outcome.STARTED);
    }
}
