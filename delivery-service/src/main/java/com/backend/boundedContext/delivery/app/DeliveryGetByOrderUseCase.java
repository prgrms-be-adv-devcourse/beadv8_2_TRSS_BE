package com.backend.boundedContext.delivery.app;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.shared.delivery.dto.DeliveryDto;

import lombok.RequiredArgsConstructor;

/**
 * 주문의 배송 정보
 */
@Component
@RequiredArgsConstructor
public class DeliveryGetByOrderUseCase {

    private final DeliveryRepository deliveryRepository;

    public Optional<DeliveryDto> findByOrderId(Long orderId) {
        return deliveryRepository.findByOrderId(orderId).map(Delivery::toDto);
    }
}
