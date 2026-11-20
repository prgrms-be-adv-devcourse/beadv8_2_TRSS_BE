package com.backend.boundedContext.delivery.app;

import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.DeliveryErrorCode;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 물류 배송 상세
 */
@Component
@RequiredArgsConstructor
public class DeliveryGetDeliveryUseCase {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryProductImageSupport deliveryProductImageSupport;

    public LogisticsDeliveryResponse getDelivery(Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new DomainException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
        return LogisticsDeliveryResponse.from(delivery, deliveryProductImageSupport.getThumbnailUrls(List.of(delivery)));
    }
}
