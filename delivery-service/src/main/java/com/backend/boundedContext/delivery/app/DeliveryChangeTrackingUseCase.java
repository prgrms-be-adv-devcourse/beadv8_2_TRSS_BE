package com.backend.boundedContext.delivery.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.TrackingNumbers;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 송장 수정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryChangeTrackingUseCase {

    private final DeliverySupport deliverySupport;

    public LogisticsDeliveryResponse changeTracking(Long deliveryId, Courier courier, String trackingNumber) {
        String normalized = TrackingNumbers.normalize(trackingNumber);
        Delivery delivery = deliverySupport.getForUpdate(deliveryId);
        deliverySupport.checkTrackingNotUsed(courier, normalized, deliveryId);

        if (delivery.changeTracking(courier, normalized)) {
            log.info("송장 수정: deliveryId={}, courier={}, trackingNumber={}", deliveryId, courier, normalized);
        }
        return LogisticsDeliveryResponse.from(delivery);
    }
}
