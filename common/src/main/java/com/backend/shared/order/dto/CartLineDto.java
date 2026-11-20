package com.backend.shared.order.dto;

/**
 * 장바구니 항목 하나의 현재 가격 정보. 상품 쿠폰 코드 검증에서 대상 항목과 예상 할인액을 계산할 때 쓴다.
 *
 * @param cartItemId 장바구니 항목 ID
 * @param productId  상품 ID. 옵션이 없어진 항목이면 null
 * @param unitPrice  현재 단가 (원). 판매할 수 없는 항목이면 0
 * @param quantity   담은 수량
 * @param saleable   지금 판매할 수 있는지 (상품 판매 중, 옵션과 카테고리 사용 중). 재고와는 무관하다
 */
public record CartLineDto(
        Long cartItemId,
        Long productId,
        long unitPrice,
        int quantity,
        boolean saleable
) {
    // 항목 금액 (단가 × 수량)
    public long lineAmount() {
        return Math.multiplyExact(unitPrice, quantity);
    }
}
