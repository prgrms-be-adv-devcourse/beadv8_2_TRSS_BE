package com.backend.boundedContext.order.in;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.out.OrderApi;

/**
 * OrderApi 구현체.
 *
 * 임시 구현(Stub)이다. 주문 엔티티가 아직 없어서 다른 컨텍스트가 먼저 개발할 수 있도록
 * "주문 없음" 기준의 고정값을 돌려준다. 주문 구현 후 {@code @Lazy OrderFacade} 호출로 바꾼다.
 */
@Component
public class OrderApiAdapter implements OrderApi {

    // 취소할 결제 대기 주문이 없으므로 0을 돌려준다
    @Override
    public int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType) {
        return 0;
    }

    // 진행 중인 주문이 없는 것으로 본다.
    @Override
    public boolean hasActiveOrders(Long buyerId) {
        return false;
    }

    // 진행 중인 판매가 없는 것으로 본다.
    @Override
    public boolean hasActiveSales(Long sellerId) {
        return false;
    }

    // 최근 주문이 없는 것으로 보고 빈 목록을 돌려준다.
    @Override
    public List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit) {
        return List.of();
    }
}
