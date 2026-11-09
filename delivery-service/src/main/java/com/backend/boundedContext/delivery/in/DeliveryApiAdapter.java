package com.backend.boundedContext.delivery.in;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.out.DeliveryApi;

import lombok.RequiredArgsConstructor;

/**
 * {@link DeliveryApi}의 구현체. DeliveryFacade 호출
 */
@Component
@RequiredArgsConstructor
public class DeliveryApiAdapter implements DeliveryApi {

    private final DeliveryFacade deliveryFacade;

    @Override
    public Optional<DeliveryDto> findByOrderId(Long orderId) {
        return deliveryFacade.findByOrderId(orderId);
    }
}
