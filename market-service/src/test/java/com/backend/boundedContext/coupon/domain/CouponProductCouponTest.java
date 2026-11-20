package com.backend.boundedContext.coupon.domain;

import com.backend.boundedContext.coupon.CouponFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coupon의 상품 쿠폰 관련 동작 (판매자 발행, 시작 전 수정, 시작 후 연장)
 */
class CouponProductCouponTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);

    @Test
    @DisplayName("상품 쿠폰을 만들면 판매자 발행, 운영 중이고 구간은 없다")
    void create() {
        Coupon coupon = Coupon.createProductCoupon(20L, "쿠폰", true, 50, NOW.plusDays(1), NOW.plusDays(5), NOW);

        assertThat(coupon.getCouponType()).isEqualTo(CouponType.PRODUCT);
        assertThat(coupon.getIssuerType()).isEqualTo(CouponIssuerType.SELLER);
        assertThat(coupon.getIssuerId()).isEqualTo(20L);
        assertThat(coupon.getRemainingQuantity()).isEqualTo(50);
        assertThat(coupon.getTiers()).isEmpty();
        assertThat(coupon.isIssuedBySeller(20L)).isTrue();
        assertThat(coupon.isIssuedBySeller(21L)).isFalse();
        assertThat(coupon.displayStatus(NOW)).isEqualTo(CouponDisplayStatus.SCHEDULED);
    }

    @Test
    @DisplayName("장바구니 쿠폰은 어떤 판매자가 발행한 것으로도 보지 않는다")
    void cartCouponNotIssuedBySeller() {
        Coupon cart = CouponFixtures.activeCartCoupon(null);

        assertThat(cart.isIssuedBySeller(CouponFixtures.ADMIN_ID)).isFalse();
    }

    @Test
    @DisplayName("시작 전에는 쿠폰명, 수량, 기간을 바꿀 수 있고 남은 수량은 새 총 수량이 된다")
    void updateBeforeStart() {
        Coupon coupon = Coupon.createProductCoupon(20L, "쿠폰", true, 50, NOW.plusDays(1), NOW.plusDays(5), NOW);

        coupon.updateBeforeStart("새 쿠폰", true, 80, NOW.plusDays(2), NOW.plusDays(9), NOW);

        assertThat(coupon.getName()).isEqualTo("새 쿠폰");
        assertThat(coupon.getTotalQuantity()).isEqualTo(80);
        assertThat(coupon.getRemainingQuantity()).isEqualTo(80);
        assertThat(coupon.getValidFrom()).isEqualTo(NOW.plusDays(2));
        assertThat(coupon.getValidUntil()).isEqualTo(NOW.plusDays(9));
    }

    @Test
    @DisplayName("시작한 뒤에는 전체 수정이 409-COUPON_NOT_EDITABLE, 틀린 기간이면 400-COUPON_PERIOD_INVALID")
    void updateBeforeStartInvalid() {
        Coupon started = Coupon.createProductCoupon(20L, "쿠폰", false, null, NOW.minusDays(1), NOW.plusDays(5), NOW);
        Coupon scheduled = Coupon.createProductCoupon(20L, "쿠폰", false, null, NOW.plusDays(1), NOW.plusDays(5), NOW);

        assertThatThrownBy(() -> started.updateBeforeStart("새", false, null, NOW.plusDays(1), NOW.plusDays(6), NOW))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_EDITABLE");
        assertThatThrownBy(() -> scheduled.updateBeforeStart("새", false, null, NOW.plusDays(6), NOW.plusDays(2), NOW))
                .extracting("resultCode").isEqualTo("400-COUPON_PERIOD_INVALID");
    }

    @Test
    @DisplayName("사용 가능 상태에서는 종료 일시를 늦출 수만 있다")
    void extendValidUntil() {
        Coupon coupon = Coupon.createProductCoupon(20L, "쿠폰", false, null, NOW.minusDays(1), NOW.plusDays(5), NOW);

        coupon.extendValidUntil(NOW.plusDays(10), NOW);

        assertThat(coupon.getValidUntil()).isEqualTo(NOW.plusDays(10));
        assertThatThrownBy(() -> coupon.extendValidUntil(NOW.plusDays(10), NOW))
                .extracting("resultCode").isEqualTo("400-COUPON_PERIOD_INVALID");
        assertThatThrownBy(() -> coupon.extendValidUntil(NOW.plusDays(3), NOW))
                .extracting("resultCode").isEqualTo("400-COUPON_PERIOD_INVALID");
    }

    @Test
    @DisplayName("시작 전, 종료, 중지 상태에서는 연장이 409-COUPON_NOT_EDITABLE")
    void extendNotEditable() {
        Coupon scheduled = Coupon.createProductCoupon(20L, "쿠폰", false, null, NOW.plusDays(1), NOW.plusDays(5), NOW);
        Coupon stopped = Coupon.createProductCoupon(20L, "쿠폰", false, null, NOW.minusDays(1), NOW.plusDays(5), NOW);
        stopped.stop(CouponStoppedBy.SELLER, null, NOW);
        Coupon ended = Coupon.createProductCoupon(20L, "쿠폰", false, null, NOW.minusDays(1), NOW.plusDays(5), NOW);

        assertThatThrownBy(() -> scheduled.extendValidUntil(NOW.plusDays(9), NOW))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_EDITABLE");
        assertThatThrownBy(() -> stopped.extendValidUntil(NOW.plusDays(9), NOW))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_EDITABLE");
        assertThatThrownBy(() -> ended.extendValidUntil(NOW.plusDays(9), NOW.plusDays(5)))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_EDITABLE");
    }
}
