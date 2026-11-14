package com.backend.shared.product.dto;

/**
 * 상품 옵션을 팔 수 없는 이유. 장바구니에서 구매 불가 사유를 보여 줄 때 쓴다.
 * 품절은 판매 불가 이유가 아니다 (재고는 stock으로 판단).
 *
 * <p>여러 개에 해당하면 선언 순서가 우선순위다 (위가 높음).
 * 삭제를 먼저 알려 줘야 주문 모듈이 반품 승인 때 삭제된 대상을 거를 수 있다.
 */
public enum ProductUnsaleableReason {
    PRODUCT_DELETED,    // 삭제된 상품
    OPTION_DELETED,     // 삭제된 옵션
    PRODUCT_STOPPED,    // 판매 중지된 상품
    CATEGORY_INACTIVE   // 카테고리가 사용 중지됨
}
