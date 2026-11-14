package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * 허용하지 않은 결제 수단 취소 결과 확인 대상 (PG 조회와 재시도에 필요한 값만)
 * - amount: 충전 금액. PG 취소 합계가 이 금액이면 전액 취소된 것
 * - attempt: 지금 취소 회차. 재시도·확정할 때 같은 회차가 아직 진행 중인지 확인함
 */
public record TopupMethodCancelTarget(
        Long topupId,
        String paymentKey,
        PgProvider pgProvider,
        long amount,
        int attempt
) {}
