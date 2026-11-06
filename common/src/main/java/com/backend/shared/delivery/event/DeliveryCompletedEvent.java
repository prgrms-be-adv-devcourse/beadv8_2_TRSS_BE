package com.backend.shared.delivery.event;

import com.backend.shared.event.DomainEvent;

import java.time.LocalDateTime;

// 배송 완료. 주문 컨텍스트가 받아 주문의 배송 완료 시각을 기록한다(구매 확정 기준)
public record DeliveryCompletedEvent(Long deliveryId, Long orderId, LocalDateTime deliveredAt) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "DELIVERY";
    }

    @Override
    public Long aggregateId() {
        return deliveryId;
    }
}
