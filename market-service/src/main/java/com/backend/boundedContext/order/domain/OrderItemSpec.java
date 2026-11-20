package com.backend.boundedContext.order.domain;

/**
 * 주문 생성 시점의 스냅샷
 *
 * @param cartItemId  원본 장바구니 항목 ID
 * @param productId   상품 ID
 * @param optionId    옵션 ID
 * @param sellerId    판매자 ID
 * @param productName 상품명 (스냅샷)
 * @param optionName  옵션명 (스냅샷)
 * @param sellerName  상호명 (스냅샷)
 * @param unitPrice     주문 단가 (스냅샷). 할인 중이면 할인가 + 추가금. 금액 계산은 이 값만 쓴다
 * @param quantity      수량
 * @param listUnitPrice 정가 단가 (스냅샷) = 상품 가격 + 추가금. 할인이 없으면 unitPrice와 같다. 표시용
 * @param discountRate  할인율 (%, 스냅샷). 할인 중이 아니면 null. 표시용
 */
public record OrderItemSpec(
        Long cartItemId,
        Long productId,
        Long optionId,
        Long sellerId,
        String productName,
        String optionName,
        String sellerName,
        long unitPrice,
        int quantity,
        long listUnitPrice,
        Integer discountRate
) {
    /**
     * 할인이 없는 항목 (listUnitPrice = unitPrice, discountRate = null).
     * 주문 도메인 테스트처럼 정가·할인율과 무관한 곳에서 쓴다. 주문 생성은 스냅샷 값을 넣는 전체 생성자를 쓴다.
     */
    public OrderItemSpec(Long cartItemId, Long productId, Long optionId, Long sellerId, String productName,
                         String optionName, String sellerName, long unitPrice, int quantity) {
        this(cartItemId, productId, optionId, sellerId, productName, optionName, sellerName, unitPrice, quantity,
                unitPrice, null);
    }
}