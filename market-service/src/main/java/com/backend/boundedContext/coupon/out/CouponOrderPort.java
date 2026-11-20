package com.backend.boundedContext.coupon.out;

import com.backend.shared.order.dto.CartLineDto;

import java.util.Collection;
import java.util.List;

/**
 * 쿠폰이 쓰는 주문 기능
 */
public interface CouponOrderPort {

    /**
     * 내 장바구니 항목들의 현재 상품 금액 합계 (판매 가능한 항목의 현재 가격 × 수량).
     * 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND.
     */
    long getCartItemsAmount(Long buyerId, Collection<Long> cartItemIds);

    /**
     * 내 장바구니 항목들의 현재 가격 정보 (상품, 단가, 수량, 판매 가능 여부). 보낸 순서대로 준다.
     * 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND.
     */
    List<CartLineDto> getCartLines(Long buyerId, Collection<Long> cartItemIds);
}
