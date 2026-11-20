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

    // 진행 중인 주문이 있는지 (회원 탈퇴 검사). 반품 승인됐는데 환불 전인 주문도 포함한다:
    // 환불 전 금액은 홀딩에 있어 예치금 잔액에 잡히지 않으므로, 이때 탈퇴하면 나중에 환불이 탈퇴한 회원 지갑으로 들어간다
    public boolean hasActiveOrders(Long buyerId) {
        return orderRepository.existsActiveOrRefundPendingByBuyerId(buyerId, Order.ACTIVE_STATUSES);
    }

    // 판매자 상품이 들어간 진행 중인 주문이 있는지
    public boolean hasActiveSales(Long sellerId) {
        return orderRepository.existsSalesBySellerIdAndStatusIn(sellerId, Order.ACTIVE_STATUSES);
    }
}
