package com.backend.boundedContext.payment.app;

// 결과 확인 대상 충전 건 (토스 조회에 필요한 값만)
public record TopupReconcileTarget(
        Long topupId,
        String orderId
) {}
