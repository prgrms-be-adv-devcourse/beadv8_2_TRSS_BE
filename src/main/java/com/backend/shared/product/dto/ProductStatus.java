package com.backend.shared.product.dto;

/**
 * 상품 상태.
 * 재고 변화로는 ON_SALE ↔ SOLD_OUT만 바뀌고, STOPPED·DELETED는 되돌릴 수 없다.
 */
public enum ProductStatus {
    ON_SALE,    // 판매 중
    SOLD_OUT,   // 품절 (재고 0)
    STOPPED,    // 판매 중지 (해제 불가)
    DELETED     // 삭제 (소프트 삭제)
}
