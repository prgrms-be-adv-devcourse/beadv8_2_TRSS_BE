package com.backend.boundedContext.delivery.in.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Delivery Tracker 웹훅 콜백 본문. 상태는 들어 있지 않고 어떤 송장인지만 알려 준다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrackerCallbackRequest(String carrierId, String trackingNumber) {
}
