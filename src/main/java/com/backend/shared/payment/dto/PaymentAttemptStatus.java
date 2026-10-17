package com.backend.shared.payment.dto;

// 결제 시도 상태 (주문 상세의 결제 내역 표시용). 결제 컨텍스트의 PaymentStatus 중 저장되는 값만 공유
public enum PaymentAttemptStatus {
    PAID,                         // 결제 완료
    REFUNDED,                     // 반품 승인으로 환불됨
    FAILED_INSUFFICIENT_BALANCE,  // 잔액 부족
    FAILED_ORDER_EXPIRED,         // 결제 시점에 주문 15분 만료
    FAILED_SYSTEM_ERROR           // 그 외 내부 오류
}
