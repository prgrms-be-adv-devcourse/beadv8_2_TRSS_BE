package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 구매 확정 공통 처리
 * 수동확정, 7일후 자동확정, 반품거절시 즉시 구매확정은 모두 이 메서드로 확정한다.
 */
@Component
@RequiredArgsConstructor
public class OrderConfirmSupport {

    private final EventPublisher eventPublisher;

    public void confirm(Order order, ConfirmType confirmType, LocalDateTime now) {
        order.confirm(confirmType, now);   // PAID가 아니면 409-ORDER_NOT_CONFIRMABLE, 이벤트는 발행하지 않음

        eventPublisher.publish(new OrderConfirmedEvent(
                order.getId(),
                order.getBuyerId(),
                order.getConfirmedAt(),
                confirmType,
                order.getItems().stream()
                        .map(OrderItem::toConfirmedOrderItemDto)
                        .toList()
        ));
    }
}
