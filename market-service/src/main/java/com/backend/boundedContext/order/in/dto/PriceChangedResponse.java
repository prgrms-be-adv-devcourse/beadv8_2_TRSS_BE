package com.backend.boundedContext.order.in.dto;

/**
 * 409-PRICE_CHANGED 응답의 data
 * 프론트가 다시 조회하지 않고 바뀐 결제 금액을 보여 주고, 그 금액으로 다시 주문할 수 있게 한다.
 *
 * @param currentAmount 지금 계산한 실제 결제 금액. 쿠폰을 쓰면 상품 쿠폰·장바구니 쿠폰 할인 후 금액이고,
 *                      쿠폰이 없으면 상품 금액 합계다. 그 순간의 금액이라 다시 주문하는 사이 또 바뀔 수 있다
 */
public record PriceChangedResponse(
        long currentAmount
) {
}
