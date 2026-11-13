package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * 취소 결과 확인 대상 충전 건 (PG 조회와 판단에 필요한 값만). 결제한 PG에 결제키로 조회함
 * - attempt: 취소 회차. 반영할 때 같은 회차가 아직 CANCELING인지 확인함
 * - canceledAmount: 이번 회차 전까지 PG 취소가 확정된 금액
 * - cancelingAmount: 이번 회차에 취소를 요청한 금액
 */
public record TopupCancelReconcileTarget(
        Long topupId,
        String paymentKey,
        PgProvider pgProvider,
        int attempt,
        long canceledAmount,
        long cancelingAmount
) {}
