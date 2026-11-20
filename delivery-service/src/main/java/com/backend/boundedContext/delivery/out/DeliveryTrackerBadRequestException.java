package com.backend.boundedContext.delivery.out;

/**
 * Delivery Tracker가 요청 자체를 거부함 (errors[0].extensions.code = BAD_REQUEST).
 */
public class DeliveryTrackerBadRequestException extends RuntimeException {

    public DeliveryTrackerBadRequestException(String message) {
        super(message);
    }
}
