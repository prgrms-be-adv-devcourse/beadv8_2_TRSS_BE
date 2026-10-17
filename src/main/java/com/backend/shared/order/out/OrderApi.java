package com.backend.shared.order.out;

import java.util.Collection;
import java.util.List;

import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderSummaryDto;

/**
 * 주문 컨텍스트가 다른 컨텍스트에 공개하는 동기 계약
 */
public interface OrderApi {

    // 상품이 들어 있는 결제 대기 주문을 모두 취소하고 재고를 복원한다.
    int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType);

    // 진행 중인 주문이 있는지 확인
    boolean hasActiveOrders(Long buyerId);

    // 판매자 상품이 들어간 진행중인 주문이 있는지 확인
    boolean hasActiveSales(Long sellerId);

    // 구매자의 최근 주문 목록 반환 (없으면 빈 목록)
    List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit);
}
