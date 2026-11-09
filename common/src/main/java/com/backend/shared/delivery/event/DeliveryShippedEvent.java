package com.backend.shared.delivery.event;

import com.backend.shared.event.DomainEvent;

import java.time.LocalDateTime;

/**
 * 배송 시작(송장 등록, READY → SHIPPED). 알림 컨텍스트가 받아 구매자에게 배송 시작 알림을 보낸다.
 * 송장 수정에서는 발행하지 않는다. 배송지·연락처는 담지 않는다.
 *
 * @param carrierName    택배사 표시 이름 (예: "CJ대한통운")
 * @param trackingNumber 정규화한 송장번호 (숫자만)
 */
public record DeliveryShippedEvent(Long deliveryId, Long orderId, Long buyerId, String carrierName,
                                   String trackingNumber, LocalDateTime shippedAt) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "DELIVERY";
    }

    @Override
    public Long aggregateId() {
        return deliveryId;
    }
}
