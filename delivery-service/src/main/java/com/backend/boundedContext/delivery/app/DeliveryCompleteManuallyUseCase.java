package com.backend.boundedContext.delivery.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 수동 배송 완료
 * 택배사 조회로 완료되지 못한 SHIPPED 배송을 관리자가 확인한 뒤 DELIVERED로 바꾸고,
 * 확인 워커와 같은 DeliveryCompletedEvent를 같은 트랜잭션에서 outbox에 저장한다.
 * 배송 행을 잠근 뒤 상태를 보므로 확인 워커(DeliveryCheckTrackingUseCase도 행을 잠그고 SHIPPED가 아니면 건너뜀)와
 * 동시에 실행돼도 한 번만 완료되고 이벤트도 한 번만 나간다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryCompleteManuallyUseCase {

    private final DeliverySupport deliverySupport;
    private final DeliveryProductImageSupport deliveryProductImageSupport;
    private final EventPublisher eventPublisher;

    public LogisticsDeliveryResponse completeManually(Long deliveryId, LocalDateTime deliveredAt, String reason,
                                                      LocalDateTime now) {
        Delivery delivery = deliverySupport.getForUpdate(deliveryId);

        delivery.completeManually(deliveredAt, reason, now);
        eventPublisher.publish(new DeliveryCompletedEvent(
                delivery.getId(), delivery.getOrderId(), delivery.getBuyerId(), deliveredAt));
        // 배송지·사유는 남기지 않는다
        log.info("관리자 수동 배송 완료: deliveryId={}, orderId={}, deliveredAt={}",
                delivery.getId(), delivery.getOrderId(), deliveredAt);
        return LogisticsDeliveryResponse.from(delivery, deliveryProductImageSupport.getThumbnailUrls(List.of(delivery)));
    }
}
