package com.backend.boundedContext.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배송비 규칙: 상품 쿠폰 할인 후 상품 금액 합계가 30,000원 이상이면 0원, 미만이면 2,500원
 */
class OrderPolicyTest {

    private final OrderPolicy policy = new OrderPolicy(15, 7, 99, 100, 1, 2500, 30000);

    @Test
    @DisplayName("기준 금액 바로 아래(29,999원)는 배송비 2,500원, 기준 금액(30,000원)부터 0원")
    void shippingFeeFor_boundary() {
        assertThat(policy.shippingFeeFor(29_999)).isEqualTo(2_500);
        assertThat(policy.shippingFeeFor(30_000)).isZero();
        assertThat(policy.shippingFeeFor(30_001)).isZero();
        assertThat(policy.shippingFeeFor(0)).isEqualTo(2_500);   // 빈 주문은 만들 수 없어 실제로는 없다
    }

    @Test
    @DisplayName("무료 배송까지 남은 금액: 25,000원이면 5,000원, 30,000원 이상이면 0원")
    void remainingForFreeShipping() {
        assertThat(policy.remainingForFreeShipping(25_000)).isEqualTo(5_000);
        assertThat(policy.remainingForFreeShipping(29_999)).isEqualTo(1);
        assertThat(policy.remainingForFreeShipping(30_000)).isZero();
        assertThat(policy.remainingForFreeShipping(45_000)).isZero();
    }

    @Test
    @DisplayName("설정 값을 그대로 읽는다")
    void getters() {
        assertThat(policy.getShippingFee()).isEqualTo(2_500);
        assertThat(policy.getFreeShippingThreshold()).isEqualTo(30_000);
    }
}
