package com.backend.boundedContext.delivery.app;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.TrackingNumbers;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * Delivery Tracker 콜백 → 확인 요청 기록
 */
@Component
@RequiredArgsConstructor
public class DeliveryRequestTrackingCheckUseCase {

    private final DeliveryRepository deliveryRepository;

    // @return 요청을 남긴 배송 건 수
    public int requestTrackingCheck(String carrierId, String trackingNumber, LocalDateTime now) {
        Optional<Courier> courier = Courier.fromCarrierId(carrierId);
        if (courier.isEmpty() || trackingNumber == null) {
            return 0;
        }
        String normalized;
        try {
            normalized = TrackingNumbers.normalize(trackingNumber);
        } catch (DomainException e) {
            return 0;   // 우리가 저장할 수 없는 형식이면 우리 송장이 아니다
        }
        return deliveryRepository.requestTrackingCheck(courier.get(), normalized, now);
    }
}
