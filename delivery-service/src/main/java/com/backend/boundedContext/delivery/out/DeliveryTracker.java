package com.backend.boundedContext.delivery.out;

import java.time.OffsetDateTime;

/**
 * Delivery Tracker(배송 조회 외부 API) 포트.
 */
public interface DeliveryTracker {

    // 송장의 마지막 배송 상태. 택배사에 없는 송장이면 {@link TrackResult#notFound()}
    TrackResult track(String carrierId, String trackingNumber);

    // 상태가 바뀔 때마다 callbackUrl로 알려 주는 웹훅을 expirationTime까지 등록한다.
    boolean registerWebhook(String carrierId, String trackingNumber, String callbackUrl, OffsetDateTime expirationTime);
}
