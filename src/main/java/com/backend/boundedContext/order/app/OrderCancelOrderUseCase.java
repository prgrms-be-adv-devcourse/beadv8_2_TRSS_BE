package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.OrderCancelResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 주문 취소
 * 결제 전 주문을 전체 취소하고 재고를 복원한다.
 */
@Component
@RequiredArgsConstructor
public class OrderCancelOrderUseCase {

    private static final String USER_CANCEL_REASON = "구매자 취소";

    private final OrderRepository orderRepository;
    private final ProductApi productApi;

    public OrderCancelResponse cancel(Long buyerId, Long orderId) {
        Order order = orderRepository.findByIdAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        order.cancel(OrderCancelType.USER, USER_CANCEL_REASON, LocalDateTime.now());

        // 재고 복원
        productApi.restoreStock(
                order.getItems().stream()
                        .map(OrderItem::toStockChangeDto)
                        .toList(),
                StockRestoreReason.ORDER_RESTORE
        );

        return new OrderCancelResponse(order.getId(), order.getOrderNumber(),
                order.getStatus(), order.getCanceledAt());
    }
}