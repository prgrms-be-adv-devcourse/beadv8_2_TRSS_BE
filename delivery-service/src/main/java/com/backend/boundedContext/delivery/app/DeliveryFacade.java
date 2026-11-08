package com.backend.boundedContext.delivery.app;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.global.page.PageResponse;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.order.event.OrderPaidEvent;

import lombok.RequiredArgsConstructor;

/**
 * 배송 컨텍스트 진입점
 */
@Service
@RequiredArgsConstructor
public class DeliveryFacade {

    private final DeliveryCreateUseCase deliveryCreateUseCase;
    private final DeliveryShipUseCase deliveryShipUseCase;
    private final DeliveryChangeTrackingUseCase deliveryChangeTrackingUseCase;
    private final DeliveryGetDeliveriesUseCase deliveryGetDeliveriesUseCase;
    private final DeliveryGetDeliveryUseCase deliveryGetDeliveryUseCase;
    private final DeliveryGetByOrderUseCase deliveryGetByOrderUseCase;

    // 결제 완료 이벤트로 배송 생성 (컨슈머 트랜잭션에 참여)
    @Transactional
    public void createFromPaidOrder(OrderPaidEvent event) {
        deliveryCreateUseCase.create(event);
    }

    // 물류 배송 목록
    @Transactional(readOnly = true)
    public PageResponse<LogisticsDeliveryResponse> getDeliveries(DeliveryStatus status, Pageable pageable) {
        return deliveryGetDeliveriesUseCase.getDeliveries(status, pageable);
    }

    // 물류 배송 상세
    @Transactional(readOnly = true)
    public LogisticsDeliveryResponse getDelivery(Long deliveryId) {
        return deliveryGetDeliveryUseCase.getDelivery(deliveryId);
    }

    // 송장 등록
    @Transactional
    public LogisticsDeliveryResponse ship(Long deliveryId, Courier courier, String trackingNumber) {
        return deliveryShipUseCase.ship(deliveryId, courier, trackingNumber, LocalDateTime.now());
    }

    // 송장 수정
    @Transactional
    public LogisticsDeliveryResponse changeTracking(Long deliveryId, Courier courier, String trackingNumber) {
        return deliveryChangeTrackingUseCase.changeTracking(deliveryId, courier, trackingNumber);
    }

    // 주문의 배송 정보 (DeliveryApi)
    @Transactional(readOnly = true)
    public Optional<DeliveryDto> findByOrderId(Long orderId) {
        return deliveryGetByOrderUseCase.findByOrderId(orderId);
    }
}
