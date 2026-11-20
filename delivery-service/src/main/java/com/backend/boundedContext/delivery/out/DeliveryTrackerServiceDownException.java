package com.backend.boundedContext.delivery.out;

/**
 * Delivery Tracker 서비스 전체가 응답하지 못함: 접속 실패·시간 초과·HTTP 5xx·해석할 수 없는 응답.
 */
public class DeliveryTrackerServiceDownException extends DeliveryTrackerUnavailableException {

    public DeliveryTrackerServiceDownException(String message) {
        super(message);
    }

    public DeliveryTrackerServiceDownException(String message, Throwable cause) {
        super(message, cause);
    }
}
