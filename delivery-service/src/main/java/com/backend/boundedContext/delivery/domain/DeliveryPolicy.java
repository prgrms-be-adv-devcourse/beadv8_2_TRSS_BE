package com.backend.boundedContext.delivery.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * 배송 컨텍스트 정책 값 (custom.delivery.*)
 * 스케줄 주기(trackingCheckIntervalSeconds 등)는 DeliveryScheduler의 @Scheduled가 직접 읽는다.
 */
@Component
@Getter
public class DeliveryPolicy {

    // 발송 후 이 일수가 지나도 SHIPPED면 안전망이 확인을 요청한다
    private final int safetyCheckAfterDays;

    // 배송 스케줄러가 한 번에 조회하는 배송 건 수
    private final int schedulerBatchSize;

    public DeliveryPolicy(
            @Value("${custom.delivery.safetyCheckAfterDays}") int safetyCheckAfterDays,
            @Value("${custom.delivery.schedulerBatchSize}") int schedulerBatchSize
    ) {
        if (safetyCheckAfterDays < 1) throw new IllegalArgumentException("custom.delivery.safetyCheckAfterDays는 1 이상이어야 합니다: " + safetyCheckAfterDays);
        if (schedulerBatchSize < 1) throw new IllegalArgumentException("custom.delivery.schedulerBatchSize는 1 이상이어야 합니다: " + schedulerBatchSize);
        this.safetyCheckAfterDays = safetyCheckAfterDays;
        this.schedulerBatchSize = schedulerBatchSize;
    }
}
