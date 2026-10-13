package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.in.dto.CartItemAddResponse;
import com.backend.boundedContext.order.in.dto.CartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 주문 컨텍스트의 유일한 진입점. 트랜잭션은 여기서만 연다.
 */
@Service
@RequiredArgsConstructor
public class OrderFacade {

    private final OrderGetCartUseCase orderGetCartUseCase;
    private final OrderAddCartItemUseCase orderAddCartItemUseCase;
    private final OrderUpdateCartItemUseCase orderUpdateCartItemUseCase;
    private final OrderSelectAllCartItemsUseCase orderSelectAllCartItemsUseCase;
    private final OrderDeleteCartItemsUseCase orderDeleteCartItemsUseCase;

    @Transactional(readOnly = true)
    public CartResponse getCart(Long memberId) {
        return orderGetCartUseCase.getCart(memberId);
    }

    @Transactional
    public CartItemAddResponse addCartItem(Long memberId, Long optionId, int quantity) {
        return orderAddCartItemUseCase.add(memberId, optionId, quantity);
    }

    @Transactional
    public void updateCartItem(Long memberId, Long itemId, Integer quantity, Boolean selected) {
        orderUpdateCartItemUseCase.update(memberId, itemId, quantity, selected);
    }

    @Transactional
    public void selectAllCartItems(Long memberId, boolean selected) {
        orderSelectAllCartItemsUseCase.selectAll(memberId, selected);
    }

    @Transactional
    public void deleteCartItems(Long memberId, List<Long> itemIds) {
        orderDeleteCartItemsUseCase.delete(memberId, itemIds);
    }
}
