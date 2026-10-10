package com.backend.shared.product.dto;

/**
 * 상품을 팔 수 없는 이유. 장바구니에서 구매 불가 사유를 보여 줄 때 쓴다.
 * 비활성 카테고리 상품은 상태가 ON_SALE이라서 상태만으로는 이유를 알 수 없으므로 따로 둔다.
 */
public enum ProductUnsaleableReason {
    SOLD_OUT,           // 품절
    STOPPED,            // 판매 중지
    DELETED,            // 삭제된 상품
    CATEGORY_INACTIVE   // 카테고리가 사용 중지됨
}
