package com.backend.boundedContext.payment.domain;

public enum PaymentStatus {
    PAID,                         // 결제 완료, 홀딩 중
    REFUNDED,                     // 반품 승인으로 홀딩 금액이 구매자 예치금으로 환불됨
    CANCELED,                     // 결제 후 주문 처리가 실패해 취소됨. 홀딩 금액을 구매자 예치금으로 되돌림
    FAILED_INSUFFICIENT_BALANCE,  // 잔액 부족. 주문은 CREATED 유지
    FAILED_ORDER_EXPIRED,         // 결제 시점에 주문 15분 만료
    FAILED_SYSTEM_ERROR;          // 그 외 내부 오류. 주문은 PAYMENT_FAILED

    public boolean isFailure() {
        return name().startsWith("FAILED_");
    }
}
