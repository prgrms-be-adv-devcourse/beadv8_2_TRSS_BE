package com.backend.boundedContext.payment.out.pg;

// PG 쪽 결제 상태. 우리 충전 상태(TopupStatus)와는 별개
public enum PgPaymentStatus {
    APPROVED,       // 승인 완료 (돈이 빠져나감)
    NOT_APPROVED,   // 승인되지 않고 끝남 (중단·만료)
    CANCELED,       // 승인 후 PG에서 취소됨
    PENDING         // 아직 확정되지 않음 (진행 중 등)
}
