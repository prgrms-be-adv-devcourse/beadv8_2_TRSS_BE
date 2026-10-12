package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 장바구니 항목 수량·선택 여부 변경
 */
@Component
@RequiredArgsConstructor
public class OrderUpdateCartItemUseCase {

    private final OrderSupport orderSupport;
    private final OrderPolicy orderPolicy;
    private final ProductApi productApi;

    public void update(Long memberId, Long itemId, Integer quantity, Boolean selected) {
        Cart cart = orderSupport.findCart(memberId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.CART_ITEM_NOT_FOUND));

        CartItem item = cart.getItem(itemId);

        if (quantity != null) {
            validateStock(item.getOptionId(), quantity);

            item.changeQuantity(quantity, orderPolicy.getCartMaxQuantity());
        }
        if (selected != null) {
            item.changeSelected(selected);
        }
    }

    // 재고검증
    private void validateStock(Long optionId, int quantity) {
        productApi.getOptions(List.of(optionId)).stream()
                .findFirst()
                .filter(option -> quantity > option.stock())
                .ifPresent(option -> {
                    throw new DomainException(OrderErrorCode.STOCK_EXCEEDED);
                });
    }
}
