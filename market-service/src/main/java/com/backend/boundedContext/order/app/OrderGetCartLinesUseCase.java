package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.CartLineDto;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장바구니 항목들의 현재 가격 정보 (OrderApi)
 *
 * <p>쿠폰 컨텍스트가 상품 쿠폰 코드를 검증할 때 대상 항목과 예상 할인액을 계산하려고 쓴다.
 * 장바구니 조회와 같은 기준으로 현재 가격을 주고, 판매할 수 없는 항목(판매 중지, 삭제된 옵션)은 saleable = false, 단가 0원이다.
 * 보낸 순서대로 주고 중복 ID는 한 번만 준다. 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND.
 */
@Component
@RequiredArgsConstructor
public class OrderGetCartLinesUseCase {

    private final OrderSupport orderSupport;
    private final ProductApi productApi;

    public List<CartLineDto> getLines(Long buyerId, Collection<Long> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return List.of();
        }

        Cart cart = orderSupport.findCart(buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.CART_ITEM_NOT_FOUND));

        List<CartItem> items = cartItemIds.stream()
                .distinct()
                .map(cart::getItem)
                .toList();

        List<Long> optionIds = items.stream().map(CartItem::getOptionId).distinct().toList();
        Map<Long, OptionSnapshotDto> options = productApi.getOptions(optionIds).stream()
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity()));

        return items.stream()
                .map(item -> toLine(item, options.get(item.getOptionId())))
                .toList();
    }

    private CartLineDto toLine(CartItem item, OptionSnapshotDto option) {
        if (option == null) {
            return new CartLineDto(item.getId(), null, 0, item.getQuantity(), false);
        }
        return new CartLineDto(item.getId(), option.productId(), option.saleable() ? option.price() : 0,
                item.getQuantity(), option.saleable());
    }
}
