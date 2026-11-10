package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.app.OrderCouponSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * 주문 생성 요청
 *
 * @param cartItemIds        주문할 장바구니 항목 ID
 * @param expectedTotalPrice 예상 결제금액. 쿠폰을 쓰면 상품 쿠폰·장바구니 쿠폰 할인 후 금액(주문서에 보여 준 금액)이고, 실제 결제 금액과 다르면 409-PRICE_CHANGED
 * @param shippingAddress    배송지. 프론트가 붙기 전까지 선택 값(생략하면 배송 없이 결제 기준으로 구매 확정되는 주문). 보내면 형식을 검증한다
 * @param productCoupons     상품 쿠폰 (선택, 한 주문에 1개까지). 주문할 장바구니 항목 중 하나에 붙인다
 * @param cartCouponId       장바구니 쿠폰 ID (선택, 한 주문에 1개)
 */
public record OrderCreateRequest(
        @NotEmpty List<@NotNull @Positive Long> cartItemIds,
        @NotNull @PositiveOrZero Long expectedTotalPrice,
        @Valid ShippingAddressRequest shippingAddress,
        @Size(max = 1) List<@NotNull @Valid ProductCouponSelectionRequest> productCoupons,
        @Positive Long cartCouponId
) {
    @AssertTrue(message = "상품 쿠폰은 주문할 장바구니 항목에만 붙일 수 있습니다.")
    public boolean isProductCouponTargetInOrder() {
        if (productCoupons == null || cartItemIds == null) {
            return true;
        }
        return productCoupons.stream()
                .filter(Objects::nonNull)
                .allMatch(coupon -> coupon.cartItemId() == null || cartItemIds.contains(coupon.cartItemId()));
    }

    // 주문 생성 유스케이스로 넘길 쿠폰 선택
    public OrderCouponSelection toCouponSelection() {
        List<OrderCouponSelection.ProductCoupon> selections = productCoupons == null ? List.of()
                : productCoupons.stream()
                .map(coupon -> new OrderCouponSelection.ProductCoupon(coupon.cartItemId(), coupon.code()))
                .toList();
        return new OrderCouponSelection(selections, cartCouponId);
    }
}
