package com.backend.boundedContext.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CourierTest {

    @Test
    @DisplayName("Delivery Tracker 택배사 ID로 찾고, 모르는 ID면 empty")
    void fromCarrierId() {
        assertThat(Courier.fromCarrierId("kr.cjlogistics")).contains(Courier.CJ);
        assertThat(Courier.fromCarrierId("kr.epost")).contains(Courier.POST);
        assertThat(Courier.fromCarrierId("kr.hanjin")).contains(Courier.HANJIN);
        assertThat(Courier.fromCarrierId("kr.lotte")).contains(Courier.LOTTE);
        assertThat(Courier.fromCarrierId("kr.logen")).isEmpty();
        assertThat(Courier.fromCarrierId(null)).isEmpty();
    }
}
