package com.backend.boundedContext.coupon.out;

import com.backend.shared.order.dto.CartLineDto;
import com.backend.shared.order.out.OrderApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link CouponOrderPort} 구현. 같은 앱(market-service)의 주문 컨텍스트를 {@link OrderApi}로 부른다
 */
@Component
@RequiredArgsConstructor
public class CouponOrderApiAdapter implements CouponOrderPort {

    private final OrderApi orderApi;

    @Override
    public long getCartItemsAmount(Long buyerId, Collection<Long> cartItemIds) {
        return orderApi.getCartItemsAmount(buyerId, cartItemIds);
    }

    @Override
    public List<CartLineDto> getCartLines(Long buyerId, Collection<Long> cartItemIds) {
        return orderApi.getCartLines(buyerId, cartItemIds);
    }
}
