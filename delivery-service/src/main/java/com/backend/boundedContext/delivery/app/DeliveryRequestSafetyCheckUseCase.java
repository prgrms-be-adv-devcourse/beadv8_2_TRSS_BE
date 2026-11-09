package com.backend.boundedContext.delivery.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.DeliveryPolicy;
import com.backend.boundedContext.delivery.out.DeliveryRepository;

import lombok.RequiredArgsConstructor;

/**
 * 안전망: 발송 후 safetyCheckAfterDays일이 지나도 SHIPPED인 배송 건에 확인 요청을 남긴다.
 * 서버가 내려가 콜백을 놓쳤거나, 웹훅을 등록하지 못한 건(한도·실패)도 확인 워커가 다시 조회하게 한다.
 */
@Component
@RequiredArgsConstructor
public class DeliveryRequestSafetyCheckUseCase {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryPolicy deliveryPolicy;

    // @return 요청을 남긴 배송 건 수
    public int requestSafetyCheck(LocalDateTime now) {
        return deliveryRepository.requestSafetyCheck(now.minusDays(deliveryPolicy.getSafetyCheckAfterDays()), now);
    }
}
