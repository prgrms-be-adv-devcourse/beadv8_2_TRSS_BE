package com.backend.boundedContext.delivery.app;

import static com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.DeliveryApplication;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.boundedContext.delivery.out.DeliveryTracker;
import com.backend.boundedContext.delivery.out.DeliveryTrackerBadRequestException;
import com.backend.boundedContext.delivery.out.TrackResult;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 확인 워커 한 건: 확인 요청이 있는 배송 건을 Delivery Tracker track으로 조회해 상태를 반영한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryCheckTrackingUseCase {

    private static final ZoneId KST = ZoneId.of(DeliveryApplication.TIME_ZONE);

    // 완료는 아니지만 사람이 볼 필요가 있는 마지막 상태 (1차는 SHIPPED 유지 + WARN)
    private static final Set<String> ATTENTION_STATUSES = Set.of("ATTEMPT_FAIL", "EXCEPTION", "AVAILABLE_FOR_PICKUP");

    private final DeliveryRepository deliveryRepository;
    private final DeliveryTracker deliveryTracker;
    private final EventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public DeliveryTaskResult checkTracking(Long deliveryId, LocalDateTime startedAt) {
        // 1. 짧은 읽기
        Delivery snapshot = deliveryRepository.findById(deliveryId).orElse(null);
        if (snapshot == null) {
            return DeliveryTaskResult.SKIPPED;
        }
        if (snapshot.getStatus() != SHIPPED) {
            transactionTemplate.executeWithoutResult(tx -> deliveryRepository.clearTrackingCheckRequest(deliveryId, startedAt));
            return DeliveryTaskResult.SKIPPED;
        }
        Courier courier = snapshot.getCourier();
        String trackingNumber = snapshot.getTrackingNumber();

        // 2. 트랜잭션 밖에서 조회
        TrackResult result;
        try {
            result = deliveryTracker.track(courier.getCarrierId(), trackingNumber);
        } catch (DeliveryTrackerBadRequestException e) {
            transactionTemplate.executeWithoutResult(tx -> deliveryRepository.clearTrackingCheckRequest(deliveryId, startedAt));
            log.warn("Delivery Tracker가 조회 요청을 거부했습니다. 요청 시각을 지우고 건너뜁니다: deliveryId={}, courier={}, reason={}",
                    deliveryId, courier, e.getMessage());
            return DeliveryTaskResult.FAILED;
        }

        // 3. 쓰기 트랜잭션
        return transactionTemplate.execute(tx -> apply(deliveryId, courier, trackingNumber, result, startedAt));
    }

    private DeliveryTaskResult apply(Long deliveryId, Courier courier, String trackingNumber,
                                     TrackResult result, LocalDateTime startedAt) {
        Delivery delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null) {
            return DeliveryTaskResult.SKIPPED;
        }
        if (delivery.getStatus() != SHIPPED) {
            // 다른 워커가 먼저 완료 처리함. 이벤트를 다시 내지 않는다
            delivery.clearTrackingCheckRequest(startedAt);
            return DeliveryTaskResult.SKIPPED;
        }
        if (!delivery.hasTracking(courier, trackingNumber)) {
            // 조회 중에 송장이 수정됨. 옛 송장의 결과라 버린다(송장 수정이 웹훅·요청 상태를 이미 비웠다)
            return DeliveryTaskResult.SKIPPED;
        }

        delivery.clearTrackingCheckRequest(startedAt);
        if (!result.found()) {
            log.info("택배사에 아직 없는 송장입니다. 다음 콜백·안전망에서 다시 확인합니다: deliveryId={}, courier={}, trackingNumber={}",
                    deliveryId, courier, trackingNumber);
            return DeliveryTaskResult.IN_PROGRESS;
        }

        delivery.recordTrackingStatus(result.statusCode());
        if (result.delivered()) {
            LocalDateTime deliveredAt = deliveredAt(result.eventTime());
            delivery.complete(deliveredAt);
            eventPublisher.publish(new DeliveryCompletedEvent(
                    delivery.getId(), delivery.getOrderId(), delivery.getBuyerId(), deliveredAt));
            log.info("배송 완료: deliveryId={}, orderId={}, deliveredAt={}", delivery.getId(), delivery.getOrderId(), deliveredAt);
            return DeliveryTaskResult.DONE;
        }
        if (ATTENTION_STATUSES.contains(result.statusCode())) {
            log.warn("배송 완료가 아닌 마지막 상태입니다: deliveryId={}, status={}", deliveryId, result.statusCode());
        }
        return DeliveryTaskResult.IN_PROGRESS;
    }

    /**
     * 배송 완료 시각: 이벤트 시각(시간대는 이벤트 위치마다 다름)을 KST로 바꾼 값.
     * 시각이 없거나 현재보다 미래면(택배사 데이터 오류) 확인 시각을 쓴다
     */
    private static LocalDateTime deliveredAt(OffsetDateTime eventTime) {
        if (eventTime == null || eventTime.toInstant().isAfter(Instant.now())) {
            return LocalDateTime.now(KST);
        }
        return eventTime.atZoneSameInstant(KST).toLocalDateTime();
    }
}
