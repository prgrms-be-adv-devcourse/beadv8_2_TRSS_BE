package com.backend.boundedContext.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 금액 구성 (상품 쿠폰 할인, 배송비, 장바구니 쿠폰 할인, 결제 금액)
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
        assertThat(order.getShippingFee()).isZero();
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

    // ===== 배송비 =====

    private static final OrderPolicy POLICY = new OrderPolicy(15, 7, 99, 100, 1, 2500, 30000);

    /** 판매자 2명(2번·5번) 주문: 각 (단가, 수량) */
    private Order twoSellerOrder(long priceA, long priceB) {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(11L, 10L, 100L, 2L, "립스틱", "기본", "상점A", priceA, 1),
                new OrderItemSpec(12L, 20L, 200L, 5L, "섀도", "기본", "상점B", priceB, 1)), NOW, 15);
        long itemId = 1;
        for (OrderItem item : order.getItems()) {
            ReflectionTestUtils.setField(item, "id", itemId++);
        }
        return order;
    }

    @Test
    @DisplayName("배송비: 판매자 2명 합 20,000원 → 배송비 2,500원은 주문당 한 번, 결제 금액 22,500원")
    void shippingFee_twoSellers_once() {
        Order order = twoSellerOrder(12_000, 8_000);

        order.applyShippingFee(POLICY.shippingFeeFor(order.getProductSubtotal()));

        assertThat(order.getShippingFee()).isEqualTo(2_500);
        assertThat(order.getPayAmount()).isEqualTo(22_500);
        assertThat(order.getPayAmountBeforeShipping()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("배송비: 합 30,000원이면 배송비 0원, 결제 금액 30,000원")
    void shippingFee_threshold() {
        Order order = twoSellerOrder(20_000, 10_000);

        order.applyShippingFee(POLICY.shippingFeeFor(order.getProductSubtotal()));

        assertThat(order.getShippingFee()).isZero();
        assertThat(order.getPayAmount()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("배송비: 합 32,000원 + 상품 쿠폰 3,000원 → 기준 29,000원이라 배송비 2,500원")
    void shippingFee_afterProductCoupon() {
        Order order = twoSellerOrder(20_000, 12_000);

        order.applyProductCouponDiscount(1L, 3_000);
        order.applyShippingFee(POLICY.shippingFeeFor(order.getProductSubtotal()));

        assertThat(order.getProductSubtotal()).isEqualTo(29_000);
        assertThat(order.getShippingFee()).isEqualTo(2_500);
        assertThat(order.getPayAmount()).isEqualTo(32_000 - 3_000 + 2_500);
        assertThat(order.getPayAmountBeforeShipping()).isEqualTo(29_000);
    }

    @Test
    @DisplayName("배송비: 합 35,000원 + 장바구니 쿠폰 5,000원 → 기준은 35,000원 그대로라 배송비 0원, 결제 금액 30,000원")
    void shippingFee_cartCouponDoesNotAffect() {
        Order order = twoSellerOrder(20_000, 15_000);

        order.applyShippingFee(POLICY.shippingFeeFor(order.getProductSubtotal()));
        order.applyCartCouponDiscount(5_000);

        assertThat(order.getShippingFee()).isZero();
        assertThat(order.getCartCouponDiscountAmount()).isEqualTo(5_000);
        assertThat(order.getPayAmount()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("배송비와 할인을 어떤 순서로 붙여도 결제 금액 = 상품 금액 - 상품 쿠폰 - 장바구니 쿠폰 + 배송비")
    void shippingFee_orderIndependent() {
        Order a = twoSellerOrder(20_000, 5_000);
        a.applyProductCouponDiscount(1L, 2_000);
        a.applyShippingFee(2_500);
        a.applyCartCouponDiscount(1_000);

        Order b = twoSellerOrder(20_000, 5_000);
        b.applyShippingFee(2_500);
        b.applyCartCouponDiscount(1_000);
        b.applyProductCouponDiscount(1L, 2_000);

        assertThat(a.getPayAmount()).isEqualTo(25_000 - 2_000 - 1_000 + 2_500);
        assertThat(b.getPayAmount()).isEqualTo(a.getPayAmount());
        assertThat(b.getPayAmountBeforeShipping()).isEqualTo(22_000);
    }

    @Test
    @DisplayName("배송비가 음수면 코드 오류다")
    void shippingFee_negative() {
        Order order = twoSellerOrder(10_000, 5_000);

        assertThatThrownBy(() -> order.applyShippingFee(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getPayAmount()).isEqualTo(15_000);
    }
}
