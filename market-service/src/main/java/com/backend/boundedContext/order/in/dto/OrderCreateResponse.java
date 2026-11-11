package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.Order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 생성 응답
 *
 * @param orderId                  주문 ID
 * @param orderNumber              주문번호
 * @param totalPrice               상품 금액 합계 (할인 전)
 * @param productDiscountAmount    상품 쿠폰 할인 합계
 * @param cartCouponDiscountAmount 장바구니 쿠폰 할인액
 * @param payAmount                결제 금액 = 상품 금액 합계 - 상품 쿠폰 할인 - 장바구니 쿠폰 할인
 * @param items                    항목별 금액과 상품 쿠폰 할인액
 * @param expiresAt                결제 만료 시각
 */
public record OrderCreateResponse(
        Long orderId,
        String orderNumber,
        long totalPrice,
        long productDiscountAmount,
        long cartCouponDiscountAmount,
        long payAmount,
        List<Item> items,
        LocalDateTime expiresAt
) {
    public static OrderCreateResponse from(Order order) {
        return new OrderCreateResponse(order.getId(), order.getOrderNumber(), order.getTotalPrice(),
                order.getProductDiscountAmount(), order.getCartCouponDiscountAmount(), order.getPayAmount(),
                order.getItems().stream()
                        .map(item -> new Item(item.getId(), item.getCartItemId(), item.getLineAmount(), item.getDiscountAmount()))
                        .toList(),
                order.getExpiresAt());
    }

    /**
     * 주문 항목 금액
     *
     * @param cartItemId     주문을 만든 장바구니 항목
     * @param lineAmount     항목 금액 (단가 x 수량)
     * @param discountAmount 상품 쿠폰 할인액 (쿠폰이 붙지 않은 항목은 0)
     */
    public record Item(
            Long orderItemId,
            Long cartItemId,
            long lineAmount,
            long discountAmount
    ) {
    }
}
