package com.backend.boundedContext.order.domain;

/**
 * 관리자 반품 목록 필터. 모두 반품을 요청한 주문(return_requested_at is not null) 안에서 고른다.
 */
public enum ReturnFilter {
    REQUESTED,  // 처리 대기 (status = RETURN_REQUESTED), 요청 시각 오래된 순
    RETURNED,   // 승인됨 (status = RETURNED)
    REJECTED,   // 거절됨 (return_rejected_at is not null, 이후 PAID 또는 CONFIRMED)
    ALL         // 반품을 요청한 모든 주문
}
