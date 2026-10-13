package com.backend.boundedContext.product.domain;

/**
 * 상품 상태 (product.status). 품절은 저장하지 않고 옵션 재고로 계산한다.
 * STOPPED·DELETED는 되돌릴 수 없다.
 */
public enum ProductSaleStatus {
    ON_SALE,    // 판매 중
    STOPPED,    // 판매 중지 (해제 불가)
    DELETED     // 삭제 (소프트 삭제)
}
