package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.PgProvider;

// 결과 확인 대상 충전 건 (PG 조회에 필요한 값만). 결제한 PG에 조회함
public record TopupReconcileTarget(
        Long topupId,
        String paymentKey,   // PROCESSING 선점(Tx1) 때 저장한 결제키
        PgProvider pgProvider
) {}
