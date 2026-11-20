package com.backend.boundedContext.payment.domain;

public enum PaymentStatus {
    READY,                        // 결제 준비
    PAID,                         // 결제 완료
    REFUNDED,                     // 환불
    CANCELED,                     // 결제 취소
    FAILED_INSUFFICIENT_BALANCE,  // 잔액 부족 실패
    FAILED_ORDER_EXPIRED,         // 주문 만료 실패
    FAILED_SYSTEM_ERROR;          // 시스템 오류 실패

    public boolean isFailure() {
        return name().startsWith("FAILED_");
    }
}
