package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.in.dto.OrderCancelResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.OrderCancelType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 취소
 */
@Component
@RequiredArgsConstructor
public class OrderCancelOrderUseCase {

    private static final String USER_CANCEL_REASON = "구매자 취소";

    private final OrderRepository orderRepository;
    private final OrderCancelSupport orderCancelSupport;

    public OrderCancelResponse cancel(Long buyerId, Long orderId) {
        // 잠금 순서: 상품 -> 주문
        List<Long> productIds = orderRepository.findProductIdsByOrderIdAndBuyerId(orderId, buyerId);
        if (productIds.isEmpty()) {
            throw new DomainException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        orderCancelSupport.lockProducts(productIds);

        Order order = orderRepository.findByIdAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        orderCancelSupport.cancel(order, OrderCancelType.USER, USER_CANCEL_REASON, LocalDateTime.now());

        return new OrderCancelResponse(order.getId(), order.getOrderNumber(),
                order.getStatus(), order.getCanceledAt());
    }
}