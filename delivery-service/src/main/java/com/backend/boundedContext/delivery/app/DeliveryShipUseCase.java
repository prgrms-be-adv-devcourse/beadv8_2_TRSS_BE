package com.backend.boundedContext.delivery.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.TrackingNumbers;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 송장 등록
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryShipUseCase {

    private final DeliverySupport deliverySupport;

    public LogisticsDeliveryResponse ship(Long deliveryId, Courier courier, String trackingNumber, LocalDateTime now) {
        String normalized = TrackingNumbers.normalize(trackingNumber);
        Delivery delivery = deliverySupport.getForUpdate(deliveryId);
        deliverySupport.checkTrackingNotUsed(courier, normalized, deliveryId);

        delivery.ship(courier, normalized, now);
        log.info("송장 등록: deliveryId={}, courier={}, trackingNumber={}", deliveryId, courier, normalized);
        return LogisticsDeliveryResponse.from(delivery);
    }
}
