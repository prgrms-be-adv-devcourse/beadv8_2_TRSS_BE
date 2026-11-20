package com.backend.boundedContext.coupon.domain;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);
    // 11월 8일 0시부터 11월 9일 18시 전까지
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 8, 0, 0);
    private static final LocalDateTime UNTIL = LocalDateTime.of(2026, 11, 9, 18, 0);

    private Coupon create(List<CartCouponTierSpec> tiers, boolean limited, Integer total,
                          LocalDateTime from, LocalDateTime until) {
        return Coupon.createCartCoupon(1L, "11월 장바구니 쿠폰", tiers, limited, total, from, until, NOW);
    }

    @Test
    @DisplayName("장바구니 쿠폰을 만들면 관리자 등록, 운영 중 상태이고 구간은 최소 주문 금액 순으로 정렬되며 남은 수량은 총 수량과 같다")
    void createCartCoupon() {
        Coupon coupon = create(List.of(
                new CartCouponTierSpec(50_000, 5_000),
                new CartCouponTierSpec(30_000, 3_000)), true, 100, FROM, UNTIL);

        assertThat(coupon.getCouponType()).isEqualTo(CouponType.CART);
        assertThat(coupon.getIssuerType()).isEqualTo(CouponIssuerType.ADMIN);
        assertThat(coupon.getIssuerId()).isEqualTo(1L);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.ACTIVE);
        assertThat(coupon.getTotalQuantity()).isEqualTo(100);
        assertThat(coupon.getRemainingQuantity()).isEqualTo(100);
        assertThat(coupon.getTiers()).extracting(CartCouponTier::getMinOrderAmount).containsExactly(30_000L, 50_000L);
        assertThat(coupon.getTiers()).extracting(CartCouponTier::getCoupon).containsOnly(coupon);
    }

    @Test
    @DisplayName("수량 제한이 없으면 총 수량과 남은 수량은 null이고 소진되지 않는다")
    void createUnlimited() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);

        assertThat(coupon.getTotalQuantity()).isNull();
        assertThat(coupon.getRemainingQuantity()).isNull();
        assertThat(coupon.isSoldOut()).isFalse();
    }

    @Test
    @DisplayName("시작이 종료보다 늦거나 같으면, 종료가 현재 이전이거나 같으면 400-COUPON_PERIOD_INVALID")
    void periodInvalid() {
        assertPeriodInvalid(UNTIL, FROM);
        assertPeriodInvalid(FROM, FROM);
        assertPeriodInvalid(NOW.minusDays(2), NOW);
        assertPeriodInvalid(NOW.minusDays(2), NOW.minusDays(1));
        assertPeriodInvalid(null, UNTIL);
        assertPeriodInvalid(FROM, null);
    }

    private void assertPeriodInvalid(LocalDateTime from, LocalDateTime until) {
        assertThatThrownBy(() -> create(CouponFixtures.TIERS, false, null, from, until))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-COUPON_PERIOD_INVALID");
    }

    @Test
    @DisplayName("시작이 현재보다 이전이어도 종료가 현재 이후면 등록할 수 있다 (등록 즉시 사용 가능)")
    void startInPast() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, NOW.minusDays(1), NOW.plusDays(1));

        assertThat(coupon.displayStatus(NOW)).isEqualTo(CouponDisplayStatus.ACTIVE);
    }

    @Test
    @DisplayName("구간이 없거나, 할인액이 최소 주문 금액 이상이거나, 금액이 겹치거나 커지지 않으면 400-COUPON_TIER_INVALID")
    void tierInvalid() {
        assertTierInvalid(List.of());
        assertTierInvalid(null);
        assertTierInvalid(List.of(new CartCouponTierSpec(3_000, 3_000)));
        assertTierInvalid(List.of(new CartCouponTierSpec(0, 0)));
        assertTierInvalid(List.of(
                new CartCouponTierSpec(30_000, 3_000),
                new CartCouponTierSpec(30_000, 5_000)));
        assertTierInvalid(List.of(
                new CartCouponTierSpec(30_000, 5_000),
                new CartCouponTierSpec(50_000, 5_000)));
        assertTierInvalid(List.of(
                new CartCouponTierSpec(30_000, 5_000),
                new CartCouponTierSpec(50_000, 3_000)));
    }

    private void assertTierInvalid(List<CartCouponTierSpec> tiers) {
        assertThatThrownBy(() -> create(tiers, false, null, FROM, UNTIL))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-COUPON_TIER_INVALID");
    }

    @Test
    @DisplayName("수량 제한인데 총 수량이 없거나 0 이하, 제한이 없는데 총 수량이 있으면 400-VALIDATION_FAILED")
    void quantityInvalid() {
        assertQuantityInvalid(true, null);
        assertQuantityInvalid(true, 0);
        assertQuantityInvalid(false, 10);
    }

    private void assertQuantityInvalid(boolean limited, Integer total) {
        assertThatThrownBy(() -> create(CouponFixtures.TIERS, limited, total, FROM, UNTIL))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-VALIDATION_FAILED");
    }

    @Test
    @DisplayName("화면 상태는 시작 전 SCHEDULED, 시작 시각부터 ACTIVE, 종료 시각부터 ENDED다 (시작 포함, 종료 미포함)")
    void displayStatus() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);

        assertThat(coupon.displayStatus(FROM.minusSeconds(1))).isEqualTo(CouponDisplayStatus.SCHEDULED);
        assertThat(coupon.displayStatus(FROM)).isEqualTo(CouponDisplayStatus.ACTIVE);
        assertThat(coupon.displayStatus(UNTIL.minusNanos(1))).isEqualTo(CouponDisplayStatus.ACTIVE);
        assertThat(coupon.displayStatus(UNTIL)).isEqualTo(CouponDisplayStatus.ENDED);
        assertThat(coupon.isUsableAt(FROM)).isTrue();
        assertThat(coupon.isUsableAt(UNTIL)).isFalse();
    }

    @Test
    @DisplayName("기간을 바꾸면 상태 컬럼을 고치지 않아도 화면 상태가 따라온다")
    void displayStatusFollowsPeriod() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);
        LocalDateTime afterEnd = UNTIL.plusHours(1);
        assertThat(coupon.displayStatus(afterEnd)).isEqualTo(CouponDisplayStatus.ENDED);

        ReflectionTestUtils.setField(coupon, "validUntil", UNTIL.plusDays(1));   // 종료 연장 (다음 이슈의 판매자 수정)

        assertThat(coupon.displayStatus(afterEnd)).isEqualTo(CouponDisplayStatus.ACTIVE);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.ACTIVE);
    }

    @Test
    @DisplayName("주문 금액이 넘는 구간 중 가장 높은 구간의 할인액 하나만 주고, 어느 구간에도 못 미치면 0이다")
    void cartDiscountFor() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);

        assertThat(coupon.cartDiscountFor(29_999)).isZero();
        assertThat(coupon.cartDiscountFor(30_000)).isEqualTo(3_000);
        assertThat(coupon.cartDiscountFor(62_000)).isEqualTo(5_000);
        assertThat(coupon.cartDiscountFor(70_000)).isEqualTo(7_000);
        assertThat(coupon.cartDiscountFor(1_000_000)).isEqualTo(7_000);
    }

    @Test
    @DisplayName("중지하면 STOPPED와 중지 주체, 사유, 시각이 남고, 종료 후에도 STOPPED로 보인다")
    void stop() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);

        coupon.stop(CouponStoppedBy.ADMIN, "부정 사용", NOW);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.STOPPED);
        assertThat(coupon.getStoppedBy()).isEqualTo(CouponStoppedBy.ADMIN);
        assertThat(coupon.getStopReason()).isEqualTo("부정 사용");
        assertThat(coupon.getStoppedAt()).isEqualTo(NOW);
        assertThat(coupon.displayStatus(NOW)).isEqualTo(CouponDisplayStatus.STOPPED);
        assertThat(coupon.displayStatus(UNTIL.plusDays(1))).isEqualTo(CouponDisplayStatus.STOPPED);
        assertThat(coupon.isUsableAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("시작 전 쿠폰도 중지할 수 있다")
    void stopScheduled() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, NOW.plusDays(1), NOW.plusDays(2));

        coupon.stop(CouponStoppedBy.ADMIN, "취소", NOW);

        assertThat(coupon.displayStatus(NOW)).isEqualTo(CouponDisplayStatus.STOPPED);
    }

    @Test
    @DisplayName("이미 중지됐거나 유효 기간이 끝난 쿠폰을 중지하면 409-COUPON_STATE_INVALID")
    void stopInvalid() {
        Coupon stopped = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);
        stopped.stop(CouponStoppedBy.ADMIN, "중지", NOW);
        Coupon ended = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);

        assertThatThrownBy(() -> stopped.stop(CouponStoppedBy.ADMIN, "다시", NOW))
                .extracting("resultCode").isEqualTo("409-COUPON_STATE_INVALID");
        assertThatThrownBy(() -> ended.stop(CouponStoppedBy.ADMIN, "끝난 뒤", UNTIL))
                .extracting("resultCode").isEqualTo("409-COUPON_STATE_INVALID");
    }

    @Test
    @DisplayName("수량 제한 쿠폰은 남은 수량이 0이면 소진이다")
    void soldOut() {
        Coupon coupon = create(CouponFixtures.TIERS, true, 1, FROM, UNTIL);
        assertThat(coupon.isSoldOut()).isFalse();

        ReflectionTestUtils.setField(coupon, "remainingQuantity", 0);   // 실제로는 조건부 UPDATE로 줄어든다

        assertThat(coupon.isSoldOut()).isTrue();
    }

    @Test
    @DisplayName("상품 쿠폰에 구간 할인을 계산하면 코드 오류(IllegalStateException)다")
    void cartDiscountForProductCoupon() {
        Coupon coupon = create(CouponFixtures.TIERS, false, null, FROM, UNTIL);
        ReflectionTestUtils.setField(coupon, "couponType", CouponType.PRODUCT);

        assertThatThrownBy(() -> coupon.cartDiscountFor(50_000)).isInstanceOf(IllegalStateException.class);
    }
}
