package com.backend.boundedContext.delivery.out;

import java.time.OffsetDateTime;

/**
 * 송장 조회 결과
 *
 * @param found      택배사에 송장이 있으면 true (NOT_FOUND면 false)
 * @param statusCode 마지막 이벤트의 상태 코드(TrackEventStatusCode: IN_TRANSIT, DELIVERED 등). 이벤트가 없으면 null
 * @param eventTime  마지막 이벤트 시각(시간대 포함). 없을 수 있다
 */
public record TrackResult(boolean found, String statusCode, OffsetDateTime eventTime) {

    public static final String DELIVERED = "DELIVERED";

    public static TrackResult notFound() {
        return new TrackResult(false, null, null);
    }

    public static TrackResult of(String statusCode, OffsetDateTime eventTime) {
        return new TrackResult(true, statusCode, eventTime);
    }

    public boolean delivered() {
        return found && DELIVERED.equals(statusCode);
    }
}
