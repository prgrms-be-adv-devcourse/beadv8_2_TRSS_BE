package com.backend.boundedContext.delivery.out;

/**
 * Delivery Tracker 인증 실패(UNAUTHENTICATED). 무료 플랜 키는 21일마다 만료되어 콘솔에서 다시 발급해야 한다.
 */
public class DeliveryTrackerUnauthenticatedException extends RuntimeException {

    public DeliveryTrackerUnauthenticatedException(String message) {
        super(message);
    }

    public DeliveryTrackerUnauthenticatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
