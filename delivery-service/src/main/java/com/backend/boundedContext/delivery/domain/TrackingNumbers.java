package com.backend.boundedContext.delivery.domain;

import java.util.regex.Pattern;

import com.backend.global.exception.DomainException;

/**
 * 송장번호 정규화. 저장·중복 비교·Delivery Tracker 호출 모두 정규화한 값을 쓴다.
 * 공백·하이픈을 지운 뒤 영문·숫자 8~30자만 허용한다.
 */
public final class TrackingNumbers {

    private static final Pattern VALID = Pattern.compile("^[0-9A-Za-z]{8,30}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");

    private TrackingNumbers() {
    }

    /** @throws DomainException 400 DELIVERY_TRACKING_NUMBER_INVALID */
    public static String normalize(String raw) {
        String normalized = raw == null ? "" : SEPARATORS.matcher(raw).replaceAll("");
        if (!VALID.matcher(normalized).matches()) {
            throw new DomainException(DeliveryErrorCode.DELIVERY_TRACKING_NUMBER_INVALID);
        }
        return normalized;
    }
}
