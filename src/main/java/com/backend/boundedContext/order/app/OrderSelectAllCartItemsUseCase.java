package com.backend.boundedContext.order.app;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 장바구니 전체 선택·해제
 */
@Component
@RequiredArgsConstructor
public class OrderSelectAllCartItemsUseCase {

    private final OrderSupport orderSupport;

    public void selectAll(Long memberId, boolean selected) {
        orderSupport.findCart(memberId)
                .ifPresent(cart -> cart.selectAll(selected));
    }
}
