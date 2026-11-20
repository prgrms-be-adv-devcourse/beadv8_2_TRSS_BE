package com.backend.boundedContext.delivery.domain;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 택배사
 */
@Getter
@RequiredArgsConstructor
public enum Courier {

    CJ("kr.cjlogistics", "CJ대한통운"),
    POST("kr.epost", "우체국택배"),
    HANJIN("kr.hanjin", "한진택배"),
    LOTTE("kr.lotte", "롯데택배");

    private final String carrierId;
    private final String displayName;

    // Delivery Tracker 택배사 ID로 찾는다. 모르는 ID면 empty
    public static Optional<Courier> fromCarrierId(String carrierId) {
        return Arrays.stream(values())
                .filter(courier -> courier.carrierId.equals(carrierId))
                .findFirst();
    }
}
