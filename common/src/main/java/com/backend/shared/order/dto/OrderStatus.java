package com.backend.shared.order.dto;

// 주문상태
public enum OrderStatus {
    CREATED,
    // 결제 확인 중: 결제 서비스에 결제를 요청했고 결과를 아직 반영하지 못함. 재확인 스케줄러가 PAID·PAYMENT_FAILED로 정리한다
    PAYING,
    PAYMENT_FAILED,
    PAID,
    CONFIRMED,
    CANCELED,
    RETURN_REQUESTED,
    RETURNED
}
