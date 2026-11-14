package com.backend.boundedContext.order.in.dto;

import java.util.List;

/**
 * 장바구니 조회 응답
 * 배송비는 선택한 주문 가능 항목의 상품 금액 합계가 30,000원 이상이면 0원, 미만이면 2,500원이다(주문당 한 번).
 * 쿠폰 할인 전 금액으로 계산하므로, 주문서에서 상품 쿠폰을 붙이면 배송비가 달라질 수 있다.
 *
 * @param items                    장바구니 항목 목록
 * @param selectedCount            선택된 항목 수
 * @param expectedTotalPrice       선택한 주문 가능 항목의 상품 금액 합계 (배송비 제외). 주문 생성 요청의 expectedTotalPrice로 보낸다
 * @param orderable                주문가능여부
 * @param shippingFee              예상 배송비. 선택한 주문 가능 항목이 없으면 0
 * @param expectedPayAmount        예상 결제 금액 = expectedTotalPrice + shippingFee (쿠폰 할인 전)
 * @param remainingForFreeShipping 무료 배송까지 남은 금액. 이미 무료 배송이거나 선택한 주문 가능 항목이 없으면 0
 */
public record CartResponse(
        List<CartItemResponse> items,
        int selectedCount,
        long expectedTotalPrice,
        boolean orderable,
        long shippingFee,
        long expectedPayAmount,
        long remainingForFreeShipping
) {
    // 장바구니가 아직 없거나 비어 있을 때
    public static CartResponse empty() {
        return new CartResponse(List.of(), 0, 0, false, 0, 0, 0);
    }
}
