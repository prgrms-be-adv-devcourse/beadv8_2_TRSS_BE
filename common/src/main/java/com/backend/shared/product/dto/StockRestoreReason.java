package com.backend.shared.product.dto;

/**
 * 재고 복원 사유.
 */
public enum StockRestoreReason {
    ORDER_RESTORE,  // 결제 전 취소, 주문 만료, 판매 중지·삭제·판매자 철회로 인한 주문 취소
    RETURN_RESTORE  // 반품 승인 (관리자가 복원을 선택한 항목만)
}
