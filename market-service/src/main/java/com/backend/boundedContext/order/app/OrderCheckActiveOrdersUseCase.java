package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.out.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 진행 중 주문 여부 확인 (다른 컨텍스트 공개용)
 */
@Component
@RequiredArgsConstructor
public class OrderCheckActiveOrdersUseCase {

    private final OrderRepository orderRepository;

    // 진행 중인 주문이 있는지
    public boolean hasActiveOrders(Long buyerId) {
        return orderRepository.existsByBuyerIdAndStatusIn(buyerId, Order.ACTIVE_STATUSES);
    }

    // 판매자 상품이 들어간 진행 중인 주문이 있는지
    public boolean hasActiveSales(Long sellerId) {
        return orderRepository.existsSalesBySellerIdAndStatusIn(sellerId, Order.ACTIVE_STATUSES);
    }
}
