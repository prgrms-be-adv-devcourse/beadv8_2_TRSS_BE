package com.backend.boundedContext.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 금액 구성 (상품 쿠폰 할인, 장바구니 쿠폰 할인, 결제 금액)
 */
class OrderPricingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0);

    /** 항목 1: 20,000원 x 2 = 40,000원, 항목 2: 15,000원 x 1 = 15,000원 (상품 금액 합계 55,000원) */
    private Order order() {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(11L, 10L, 100L, 2L, "립스틱", "기본", "상점A", 20_000, 2),
                new OrderItemSpec(12L, 20L, 200L, 2L, "섀도", "기본", "상점A", 15_000, 1)), NOW, 15);
        long itemId = 1;
        for (OrderItem item : order.getItems()) {
            ReflectionTestUtils.setField(item, "id", itemId++);
        }
        return order;
    }

    @Test
    @DisplayName("만들 때는 할인이 0이고 결제 금액은 상품 금액 합계와 같다")
    void created() {
        Order order = order();

        assertThat(order.getTotalPrice()).isEqualTo(55_000);
        assertThat(order.getProductDiscountAmount()).isZero();
        assertThat(order.getCartCouponDiscountAmount()).isZero();
        assertThat(order.getPayAmount()).isEqualTo(55_000);
        assertThat(order.getProductSubtotal()).isEqualTo(55_000);
    }

    @Test
    @DisplayName("상품 쿠폰 → 장바구니 쿠폰 순서로 반영하면 결제 금액 = 상품 금액 - 상품 쿠폰 - 장바구니 쿠폰")
    void pricing() {
        Order order = order();

        order.applyProductCouponDiscount(1L, 30_000);   // 항목 1: 40,000 → 10,000
        order.applyCartCouponDiscount(3_000);           // 할인 후 25,000원 기준 장바구니 쿠폰 3,000원

        assertThat(order.getItems().getFirst().getDiscountAmount()).isEqualTo(30_000);
        assertThat(order.getItems().getFirst().getDiscountedLineAmount()).isEqualTo(10_000);
        assertThat(order.getItems().get(1).getDiscountAmount()).isZero();
        assertThat(order.getProductDiscountAmount()).isEqualTo(30_000);
        assertThat(order.getProductSubtotal()).isEqualTo(25_000);
        assertThat(order.getCartCouponDiscountAmount()).isEqualTo(3_000);
        assertThat(order.getPayAmount()).isEqualTo(55_000 - 30_000 - 3_000);
    }

    @Test
    @DisplayName("상품 쿠폰 할인이 항목 금액을 넘거나 음수이거나, 없는 항목이면 코드 오류다")
    void invalidProductDiscount() {
        Order order = order();

        assertThatThrownBy(() -> order.applyProductCouponDiscount(1L, 40_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.applyProductCouponDiscount(1L, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.applyProductCouponDiscount(99L, 1_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getPayAmount()).isEqualTo(55_000);
    }

    @Test
    @DisplayName("정책상 0원 결제는 없다: 상품 쿠폰 할인은 항목 금액보다, 장바구니 쿠폰 할인은 상품 쿠폰 할인 후 금액보다 작아야 하고 음수가 될 수 없다")
    void invalidCartCouponDiscount() {
        Order order = order();
        assertThatThrownBy(() -> order.applyProductCouponDiscount(1L, 40_000)).isInstanceOf(IllegalArgumentException.class);   // 항목 금액 전부

        order.applyProductCouponDiscount(1L, 39_999);   // 할인 후 15,001원
        assertThatThrownBy(() -> order.applyCartCouponDiscount(15_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.applyCartCouponDiscount(-1)).isInstanceOf(IllegalArgumentException.class);

        order.applyCartCouponDiscount(15_000);
        assertThat(order.getPayAmount()).isEqualTo(1);   // 최소 1원은 남는다
    }
}
