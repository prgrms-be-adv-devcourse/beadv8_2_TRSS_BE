package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.global.exception.DomainException;
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
 * 장바구니 항목들의 현재 상품 금액 합계 (OrderApi)
 *
 * <p>쿠폰 컨텍스트가 장바구니 쿠폰의 예상 할인액을 보여 줄 때 쓴다.
 * 장바구니 조회와 같은 기준으로, 판매 가능한 항목만 현재 가격 × 수량으로 더한다(판매 중지, 삭제된 옵션은 0원).
 * 재고와 결제 대기 주문 여부는 보지 않는다(실제 금액은 주문 생성 때 다시 계산한다).
 * 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND.
 */
@Component
@RequiredArgsConstructor
public class OrderGetCartItemsAmountUseCase {

    private final OrderSupport orderSupport;
    private final ProductApi productApi;

    public long getAmount(Long buyerId, Collection<Long> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return 0;
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
                .mapToLong(item -> lineAmount(item, options.get(item.getOptionId())))
                .sum();
    }

    private long lineAmount(CartItem item, OptionSnapshotDto option) {
        if (option == null || !option.saleable()) {
            return 0;
        }
        return Math.multiplyExact(option.price(), item.getQuantity());
    }
}
