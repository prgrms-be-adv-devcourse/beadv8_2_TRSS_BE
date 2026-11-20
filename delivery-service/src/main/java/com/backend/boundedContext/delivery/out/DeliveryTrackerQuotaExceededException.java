package com.backend.boundedContext.delivery.out;

/**
 * Delivery Tracker 할당량 초과(HTTP 429 또는 할당량 오류 코드). 이번 회차를 멈추고 다음 회차에 이어 한다
 */
public class DeliveryTrackerQuotaExceededException extends RuntimeException {

    public DeliveryTrackerQuotaExceededException(String message) {
        super(message);
    }

    public DeliveryTrackerQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
