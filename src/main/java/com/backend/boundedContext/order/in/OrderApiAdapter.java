package com.backend.boundedContext.order.in;

import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.out.OrderApi;

import lombok.RequiredArgsConstructor;

/**
 * {@link OrderApi} 구현체
 */
@Component
@RequiredArgsConstructor
public class OrderApiAdapter implements OrderApi {

    @Lazy   // 컨텍스트 간 순환 참조 방지 (Order → ProductApi → OrderApi)
    private final OrderFacade orderFacade;

    // TODO: 임시 구현. ProductApi.stopAllBySeller 연동 시 결제 대기 주문 취소 + 재고 복원으로 바꾼다.
    @Override
    public int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType) {
        return 0;
    }

    @Override
    public boolean hasActiveOrders(Long buyerId) {
        return orderFacade.hasActiveOrders(buyerId);
    }

    @Override
    public boolean hasActiveSales(Long sellerId) {
        return orderFacade.hasActiveSales(sellerId);
    }

    @Override
    public List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit) {
        return orderFacade.getRecentOrders(buyerId, limit);
    }
}
