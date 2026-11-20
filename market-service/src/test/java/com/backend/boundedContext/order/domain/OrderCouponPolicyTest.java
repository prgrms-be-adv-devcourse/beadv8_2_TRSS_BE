package com.backend.boundedContext.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCouponPolicyTest {

    @Test
    @DisplayName("설정값 그대로 쿠폰 반영 여부를 알려 준다")
    void couponEnabled() {
        assertThat(new OrderCouponPolicy(true).isCouponEnabled()).isTrue();
        assertThat(new OrderCouponPolicy(false).isCouponEnabled()).isFalse();
    }
}
