package com.backend.shared.admin.dto;

/**
 * 관리자 실패 거래 화면(A7)의 유형. 화면의 탭과 배지가 이 값으로 나뉜다
 * 서비스마다 자기 유형만 돌려준다
 */
public enum AdminFailureType {
    /** 결제 실패 (payment) */
    PAYMENT_FAILED,
    /** 충전 확인 지연, 충전 실패 (payment) */
    TOPUP_DELAYED,
    /** 환불 실패 (payment 환불) */
    REFUND_FAILED,
    /** 정산 수동 처리 필요 (settlement) */
    SETTLEMENT_MANUAL_REQUIRED,
    /** 배송 추적 실패 (delivery) */
    DELIVERY_TRACKING_FAILED
}
