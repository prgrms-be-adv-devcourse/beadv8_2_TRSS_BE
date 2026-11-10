package com.backend.boundedContext.delivery.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.TrackingNumbers;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.delivery.event.DeliveryShippedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 송장 등록
 * 같은 트랜잭션에서 배송 시작 이벤트(DeliveryShippedEvent)를 outbox에 저장한다.
 * 상태 검사·중복 검사에서 실패하면 예외로 끝나 이벤트도 남지 않는다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryShipUseCase {

    private final DeliverySupport deliverySupport;
    private final EventPublisher eventPublisher;

    public LogisticsDeliveryResponse ship(Long deliveryId, Courier courier, String trackingNumber, LocalDateTime now) {
        String normalized = TrackingNumbers.normalize(trackingNumber);
        Delivery delivery = deliverySupport.getForUpdate(deliveryId);
        deliverySupport.checkTrackingNotUsed(courier, normalized, deliveryId);

        delivery.ship(courier, normalized, now);
        eventPublisher.publish(new DeliveryShippedEvent(delivery.getId(), delivery.getOrderId(), delivery.getBuyerId(),
                delivery.carrierName(), delivery.getTrackingNumber(), delivery.getShippedAt()));
        log.info("송장 등록: deliveryId={}, courier={}, trackingNumber={}", deliveryId, courier, normalized);
        return LogisticsDeliveryResponse.from(delivery);
    }
}
