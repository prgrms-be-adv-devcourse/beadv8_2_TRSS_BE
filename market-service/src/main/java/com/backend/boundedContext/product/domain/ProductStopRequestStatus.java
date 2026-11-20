package com.backend.boundedContext.product.domain;

/** 판매 중지 요청 상태 (product_stop_request.status) */
public enum ProductStopRequestStatus {
    PENDING,    // 판매자가 요청, 관리자 검토 대기
    APPROVED,   // 관리자 승인 → 상품 STOPPED
    REJECTED,   // 관리자 반려
    CANCELED    // 심사 중에 상품이 삭제되거나 다른 이유로 중지되어 닫힘
}
