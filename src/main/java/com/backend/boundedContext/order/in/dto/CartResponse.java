package com.backend.boundedContext.order.in.dto;

import java.util.List;

/**
 * 장바구니 조회 응답
 *
 * @param items              장바구니 항목 목록
 * @param selectedCount      선택된 항목 수
 * @param expectedTotalPrice 예상결제금액
 * @param orderable          주문가능여부
 */
public record CartResponse(
        List<CartItemResponse> items,
        int selectedCount,
        long expectedTotalPrice,
        boolean orderable
) {
    // 장바구니가 아직 없거나 비어 있을 때
    public static CartResponse empty() {
        return new CartResponse(List.of(), 0, 0, false);
    }
}
