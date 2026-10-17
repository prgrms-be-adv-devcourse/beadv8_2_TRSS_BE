package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 판매자 주문 항목 한 줄
 *
 * @param orderedAt  주문일
 * @param lineAmount 금액 (단가×수량)
 * @param status     주문 상태
 */
public record SellerOrderItemResponse(
        Long orderItemId,
        Long orderId,
        String orderNumber,
        LocalDateTime orderedAt,
        Long productId,
        String productName,
        String optionName,
        int quantity,
        long unitPrice,
        long lineAmount,
        OrderStatus status
) {
    public static SellerOrderItemResponse from(OrderItem item) {
        Order order = item.getOrder();
        return new SellerOrderItemResponse(
                item.getId(),
                order.getId(),
                order.getOrderNumber(),
                order.getCreatedAt(),
                item.getProductId(),
                item.getProductName(),
                item.getOptionName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineAmount(),
                order.getStatus()
        );
    }
}
