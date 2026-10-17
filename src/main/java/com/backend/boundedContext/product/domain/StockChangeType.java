package com.backend.boundedContext.product.domain;

/**
 * 재고 변경 사유 (stock_history.change_type).
 */
public enum StockChangeType {
    INIT,           // 상품 등록 시 초기 재고
    ADJUST,         // 판매자 수동 조정
    ORDER_DEDUCT,   // 주문 생성 시 차감
    ORDER_RESTORE,  // 결제 전 취소·만료·판매 중지로 인한 주문 취소 시 복원
    RETURN_RESTORE  // 반품 승인 시 복원 (관리자가 선택한 항목만)
}
