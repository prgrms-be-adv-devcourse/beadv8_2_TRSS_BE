package com.backend.boundedContext.delivery.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
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
    private final DeliveryRequestTrackingCheckUseCase deliveryRequestTrackingCheckUseCase;
    private final DeliveryCheckTrackingUseCase deliveryCheckTrackingUseCase;
    private final DeliveryRegisterWebhookUseCase deliveryRegisterWebhookUseCase;
    private final DeliveryRequestSafetyCheckUseCase deliveryRequestSafetyCheckUseCase;
    private final DeliveryRepository deliveryRepository;

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

    // 콜백 → 확인 요청 기록 (UPDATE 한 번)
    @Transactional
    public int requestTrackingCheck(String carrierId, String trackingNumber) {
        return deliveryRequestTrackingCheckUseCase.requestTrackingCheck(carrierId, trackingNumber, LocalDateTime.now());
    }

    // 확인 워커 한 건. 외부 조회를 트랜잭션 밖에 두려고 트랜잭션은 UseCase가 TransactionTemplate으로 연다
    public DeliveryTaskResult checkTracking(Long deliveryId, LocalDateTime startedAt) {
        return deliveryCheckTrackingUseCase.checkTracking(deliveryId, startedAt);
    }

    // 웹훅 등록 한 건 (새 등록·재등록). 트랜잭션은 UseCase가 연다
    public DeliveryTaskResult registerWebhook(Long deliveryId, LocalDateTime now) {
        return deliveryRegisterWebhookUseCase.registerWebhook(deliveryId, now);
    }

    // 안전망: 발송 후 오래 SHIPPED인 배송 건에 확인 요청
    @Transactional
    public int requestSafetyCheck(LocalDateTime now) {
        return deliveryRequestSafetyCheckUseCase.requestSafetyCheck(now);
    }

    @Transactional(readOnly = true)
    public List<Long> findTrackingCheckTargetIds(Long afterId, int limit) {
        return deliveryRepository.findTrackingCheckTargetIds(afterId, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<Long> findWebhookRegisterTargetIds(Long afterId, int limit) {
        return deliveryRepository.findWebhookRegisterTargetIds(afterId, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<Long> findWebhookRenewTargetIds(LocalDateTime threshold, Long afterId, int limit) {
        return deliveryRepository.findWebhookRenewTargetIds(threshold, afterId, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public long countActiveWebhooks(LocalDateTime now) {
        return deliveryRepository.countActiveWebhooks(now);
    }
}
