package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.in.dto.OrderConfirmResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ConfirmType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 구매자 수동 구매 확정
 * 결제 완료주문을 확정하고 OrderConfirmedEvent를 발행한다.
 */
@Component
@RequiredArgsConstructor
public class OrderConfirmOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderConfirmSupport orderConfirmSupport;

    public OrderConfirmResponse confirm(Long buyerId, Long orderId) {
        // 자동 확정·반품 요청과 겹칠 수 있어 주문 행을 잠가서 조회
        Order order = orderRepository.findByIdAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        orderConfirmSupport.confirm(order, ConfirmType.MANUAL, LocalDateTime.now());

        return new OrderConfirmResponse(order.getId(), order.getOrderNumber(),
                order.getStatus(), order.getConfirmType(), order.getConfirmedAt());
    }
}
