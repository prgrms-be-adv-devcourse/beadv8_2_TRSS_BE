package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.in.dto.OrderPaymentResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.out.PaymentApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 상세
 */
@Component
@RequiredArgsConstructor
public class OrderGetOrderDetailUseCase {

    private final OrderRepository orderRepository;
    private final PaymentApi paymentApi;

    public OrderDetailResponse getOrderDetail(Long buyerId, Long orderId) {
        Order order = orderRepository.findWithItemsByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        // 내 주문인지 확인한 뒤에 결제 내역을 조회한다
        List<OrderPaymentResponse> payments = paymentApi.getPaymentAttempts(order.getId()).stream()
                .map(OrderPaymentResponse::from)
                .toList();

        return toDetail(order, LocalDateTime.now(), payments);
    }

    private OrderDetailResponse toDetail(Order order, LocalDateTime now, List<OrderPaymentResponse> payments) {
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
                order.getItems().stream().map(this::toItem).toList(),
                payments
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