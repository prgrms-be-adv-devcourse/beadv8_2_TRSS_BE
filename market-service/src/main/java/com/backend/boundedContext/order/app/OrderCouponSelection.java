package com.backend.boundedContext.order.app;

import java.util.List;

/**
 * 주문서에서 고른 쿠폰
 *
 * @param productCoupons 상품 쿠폰 (한 주문에 1개까지). 비어 있으면 상품 쿠폰 없음
 * @param cartCouponId   장바구니 쿠폰 ID. null이면 장바구니 쿠폰 없음
 */
public record OrderCouponSelection(
        List<ProductCoupon> productCoupons,
        Long cartCouponId
) {
    public static final OrderCouponSelection NONE = new OrderCouponSelection(List.of(), null);

    public OrderCouponSelection {
        productCoupons = productCoupons == null ? List.of() : List.copyOf(productCoupons);
    }

    public boolean isEmpty() {
        return productCoupons.isEmpty() && cartCouponId == null;
    }

    /**
     * @param cartItemId 할인을 붙일 장바구니 항목
     * @param code       상품 쿠폰 코드
     */
    public record ProductCoupon(Long cartItemId, String code) {
    }
}
