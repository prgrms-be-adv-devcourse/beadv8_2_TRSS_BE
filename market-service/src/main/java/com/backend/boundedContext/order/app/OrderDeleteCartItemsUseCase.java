package com.backend.boundedContext.order.app;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 장바구니 항목 삭제
 */
@Component
@RequiredArgsConstructor
public class OrderDeleteCartItemsUseCase {

    private final OrderSupport orderSupport;

    public void delete(Long memberId, List<Long> itemIds) {
        orderSupport.findCart(memberId)
                .ifPresent(cart -> cart.removeItems(itemIds));
    }
}
