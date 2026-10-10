package com.backend.shared.product.dto;

/**
 * 재고 차감·복원 요청 한 건. 주문 항목 한 줄이 하나다.
 * orderId·orderItemId는 재고 이력에 남고, 같은 주문 항목이 두 번 처리되지 않게 하는 기준이 된다.
 *
 * @param orderId     주문 ID
 * @param orderItemId 주문 항목 ID (주문 항목을 저장한 뒤의 값)
 * @param productId   상품 ID
 * @param quantity    수량. 차감·복원 모두 1 이상의 양수로 보낸다
 */
public record StockChangeDto(
        Long orderId,
        Long orderItemId,
        Long productId,
        int quantity
) {
}
