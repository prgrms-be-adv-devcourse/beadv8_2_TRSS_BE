package com.backend.boundedContext.delivery.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.DeliveryErrorCode;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 송장 등록·수정이 함께 쓰는 조회·검사
 */
@Component
@RequiredArgsConstructor
class DeliverySupport {

    private final DeliveryRepository deliveryRepository;

    // 배송 행을 잠가서 조회. 없으면 404
    Delivery getForUpdate(Long deliveryId) {
        return deliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new DomainException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    }

    // 다른 배송 건에 같은 택배사·송장번호가 있으면 409. 동시에 등록하면 DB 유니크(uk_delivery_tracking)가 막는다
    void checkTrackingNotUsed(Courier courier, String trackingNumber, Long deliveryId) {
        if (deliveryRepository.existsByCourierAndTrackingNumberAndIdNot(courier, trackingNumber, deliveryId)) {
            throw new DomainException(DeliveryErrorCode.DELIVERY_TRACKING_DUPLICATED);
        }
    }
}
