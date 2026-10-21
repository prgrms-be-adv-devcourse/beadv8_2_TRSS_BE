package com.backend.boundedContext.product.domain;

/**
 * 판매자 화면에 보여 주는 상품 상태. 품절은 저장하지 않고 재고로 계산한다.
 * 삭제된 상품은 판매자 목록에 나오지 않으므로 값이 없다.
 */
public enum ProductDisplayStatus {
    ON_SALE,    // 판매 중이고 재고가 있음
    SOLD_OUT,   // 판매 중이지만 재고가 모두 0
    STOPPED;    // 판매 중지

    public static ProductDisplayStatus of(Product product, boolean soldOut) {
        if (product.isStopped()) return STOPPED;
        return soldOut ? SOLD_OUT : ON_SALE;
    }
}
