package com.backend.boundedContext.delivery.app;

import static com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.DeliveryApplication;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.boundedContext.delivery.out.DeliveryTracker;
import com.backend.boundedContext.delivery.out.DeliveryTrackerBadRequestException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 웹훅 등록 한 건 (새 등록·재등록 공통)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryRegisterWebhookUseCase {

    private static final ZoneId KST = ZoneId.of(DeliveryApplication.TIME_ZONE);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryTracker deliveryTracker;
    private final DeliveryTrackerPolicy deliveryTrackerPolicy;
    private final TransactionTemplate transactionTemplate;

    public DeliveryTaskResult registerWebhook(Long deliveryId, LocalDateTime now) {
        Delivery snapshot = deliveryRepository.findById(deliveryId).orElse(null);
        if (snapshot == null || snapshot.getStatus() != SHIPPED) {
            return DeliveryTaskResult.SKIPPED;
        }
        Courier courier = snapshot.getCourier();
        String trackingNumber = snapshot.getTrackingNumber();
        LocalDateTime expiresAt = now.plusHours(deliveryTrackerPolicy.getWebhookTtlHours());
        OffsetDateTime expirationTime = expiresAt.atZone(KST).toOffsetDateTime();

        boolean registered;
        try {
            registered = deliveryTracker.registerWebhook(courier.getCarrierId(), trackingNumber,
                    deliveryTrackerPolicy.callbackUrl(), expirationTime);
        } catch (DeliveryTrackerBadRequestException e) {
            // 메시지는 Delivery Tracker 오류 메시지뿐이다(토큰은 클라이언트가 가림). 콜백 URL은 남기지 않는다
            log.warn("Delivery Tracker가 웹훅 등록을 거부했습니다. 이 건은 건너뜁니다: deliveryId={}, courier={}, reason={}",
                    deliveryId, courier, e.getMessage());
            return DeliveryTaskResult.SKIPPED;
        }
        if (!registered) {
            log.warn("웹훅 등록에 실패했습니다. 다음 회차에 다시 합니다: deliveryId={}, reason={}",
                    deliveryId, "registerTrackWebhook=false");
            return DeliveryTaskResult.FAILED;
        }

        Integer updated = transactionTemplate.execute(tx ->
                deliveryRepository.markWebhookRegistered(deliveryId, courier, trackingNumber, expiresAt));
        // 0이면 등록하는 동안 송장이 바뀌었거나 배송이 끝났다. 새 송장은 다음 회차에 등록한다
        return updated != null && updated == 1 ? DeliveryTaskResult.DONE : DeliveryTaskResult.SKIPPED;
    }
}
