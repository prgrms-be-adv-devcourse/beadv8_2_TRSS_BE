package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 주문 상세
 */
@Component
@RequiredArgsConstructor
public class OrderGetOrderDetailUseCase {

    private final OrderRepository orderRepository;

    public OrderDetailResponse getOrderDetail(Long buyerId, Long orderId) {
        Order order = orderRepository.findWithItemsByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        return toDetail(order, LocalDateTime.now());
    }

    private OrderDetailResponse toDetail(Order order, LocalDateTime now) {
        return new OrderDetailResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getCreatedAt(),
                order.getExpiresAt(),
                order.remainingPaymentSeconds(now),
                order.getPaidAt(),
                order.getCanceledAt(),
                order.getConfirmedAt(),
                order.getItems().stream().map(this::toItem).toList()
        );
    }

    private OrderItemResponse toItem(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getProductId(),
                item.getOptionId(),
                item.getProductName(),
                item.getOptionName(),
                item.getSellerName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineAmount()
        );
    }
}