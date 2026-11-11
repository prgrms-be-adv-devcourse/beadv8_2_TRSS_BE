package com.backend.boundedContext.coupon.domain;

import com.backend.boundedContext.coupon.CouponFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductCouponTest {

    private ProductCoupon create(ProductCouponDiscount discount) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = CouponFixtures.productCouponBase(now.minusDays(1), now.plusDays(1), null);
        return CouponFixtures.productCoupon(coupon, 1L, "ABCDEFGH23", discount);
    }

    @Test
    @DisplayName("정액 할인은 할인액을 그대로 주고, 할인 후 금액이 0원이 되면(할인액 >= 항목 금액) 400-COUPON_NOT_APPLICABLE")
    void fixed() {
        ProductCoupon coupon = create(new ProductCouponDiscount(DiscountType.FIXED, 3_000, null));

        assertThat(coupon.discountFor(20_000)).isEqualTo(3_000);
        assertThat(coupon.discountFor(3_001)).isEqualTo(3_000);   // 1원은 남는다
        assertThatThrownBy(() -> coupon.discountFor(3_000)).extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
        assertThatThrownBy(() -> coupon.discountFor(2_000)).extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
        assertThatThrownBy(() -> coupon.discountFor(0)).extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("정률 할인은 원 단위 버림 후 최대 할인액으로 자른다")
    void rate() {
        ProductCoupon coupon = create(new ProductCouponDiscount(DiscountType.RATE, 15, 5_000L));

        assertThat(coupon.discountFor(19_999)).isEqualTo(2_999);   // 2,999.85 → 2,999
        assertThat(coupon.discountFor(30_000)).isEqualTo(4_500);
        assertThat(coupon.discountFor(100_000)).isEqualTo(5_000);   // 15,000 → 최대 5,000
    }

    @Test
    @DisplayName("100% 정률 쿠폰은 발급하지 않고(400-VALIDATION_FAILED), 99%는 원 단위 버림이라 항목 금액보다 작다")
    void rateFull() {
        assertInvalid(new ProductCouponDiscount(DiscountType.RATE, 100, 1_000_000L));

        ProductCoupon coupon = create(new ProductCouponDiscount(DiscountType.RATE, 99, 1_000_000L));
        assertThat(coupon.discountFor(12_345)).isEqualTo(12_221);   // 12,345 × 99 ÷ 100 = 12,221.55 → 12,221
    }

    @Test
    @DisplayName("할인 내용이 할인 방식과 맞지 않으면 400-VALIDATION_FAILED")
    void invalidDiscount() {
        assertInvalid(new ProductCouponDiscount(DiscountType.FIXED, 0, null));
        assertInvalid(new ProductCouponDiscount(DiscountType.FIXED, 3_000, 5_000L));
        assertInvalid(new ProductCouponDiscount(DiscountType.RATE, 101, 5_000L));
        assertInvalid(new ProductCouponDiscount(DiscountType.RATE, 10, null));
        assertInvalid(new ProductCouponDiscount(DiscountType.RATE, 10, 0L));
    }

    private void assertInvalid(ProductCouponDiscount discount) {
        assertThatThrownBy(() -> create(discount)).extracting("resultCode").isEqualTo("400-VALIDATION_FAILED");
    }

    @Test
    @DisplayName("할인 내용을 바꾸면 새 값으로 계산하고, 틀린 값이면 바꾸지 않는다")
    void changeDiscount() {
        ProductCoupon coupon = create(CouponFixtures.FIXED_3000);

        coupon.changeDiscount(new ProductCouponDiscount(DiscountType.RATE, 10, 2_000L));

        assertThat(coupon.discount()).isEqualTo(new ProductCouponDiscount(DiscountType.RATE, 10, 2_000L));
        assertThat(coupon.discountFor(30_000)).isEqualTo(2_000);
        assertThatThrownBy(() -> coupon.changeDiscount(new ProductCouponDiscount(DiscountType.RATE, 10, null)))
                .extracting("resultCode").isEqualTo("400-VALIDATION_FAILED");
        assertThat(coupon.getDiscountType()).isEqualTo(DiscountType.RATE);
    }
}
