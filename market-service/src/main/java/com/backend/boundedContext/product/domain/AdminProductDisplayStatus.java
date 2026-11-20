package com.backend.boundedContext.product.domain;

/**
 * 관리자 화면에 보여 주는 상품 상태. 판매자 화면 상태({@link ProductDisplayStatus})에 삭제됨을 더했다.
 * 품절은 저장하지 않고 재고로 계산한다.
 */
public enum AdminProductDisplayStatus {
    ON_SALE,    // 판매 중이고 재고가 있음
    SOLD_OUT,   // 판매 중이지만 재고가 모두 0
    STOPPED,    // 판매 중지
    DELETED;    // 판매자가 삭제함

    public static AdminProductDisplayStatus of(Product product, boolean soldOut) {
        if (product.isDeleted()) return DELETED;
        if (product.isStopped()) return STOPPED;
        return soldOut ? SOLD_OUT : ON_SALE;
    }
}
