package com.backend.boundedContext.order.in.dto;

/**
 * 주문 상세의 환불 상태 (market이 주문 상태와 결제 시도로 계산한다. 결제 담당과 합의 전 기본안)
 */
public enum RefundStatus {
    NONE,       // 반품 승인(RETURNED)된 주문이 아님
    PENDING,    // 반품 승인됐고 환불(결제 시도 REFUNDED)이 아직 없음
    COMPLETED,  // 반품 승인됐고 결제 시도 중 REFUNDED가 있음
    UNKNOWN     // 반품 승인됐지만 결제 내역을 조회하지 못함 (payment 장애)
}
