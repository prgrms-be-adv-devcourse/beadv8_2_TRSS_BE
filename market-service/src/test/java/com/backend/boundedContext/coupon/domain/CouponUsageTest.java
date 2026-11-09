package com.backend.boundedContext.coupon.domain;

import com.backend.boundedContext.coupon.CouponFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponUsageTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);

    private CouponUsage reserved() {
        return CouponUsage.reserve(CouponFixtures.activeCartCoupon(null), 7L, 500L, null, 5_000, NOW);
    }

    @Test
    @DisplayName("예약하면 RESERVED와 예약 시각, 할인액이 남는다")
    void reserve() {
        CouponUsage usage = reserved();

        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.RESERVED);
        assertThat(usage.getMemberId()).isEqualTo(7L);
        assertThat(usage.getOrderId()).isEqualTo(500L);
        assertThat(usage.getOrderItemId()).isNull();
        assertThat(usage.getDiscountAmount()).isEqualTo(5_000);
        assertThat(usage.getReservedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("예약을 사용 처리하면 USED와 사용 시각이 남는다")
    void use() {
        CouponUsage usage = reserved();

        usage.use(NOW.plusMinutes(3));

        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.USED);
        assertThat(usage.getUsedAt()).isEqualTo(NOW.plusMinutes(3));
    }

    @Test
    @DisplayName("예약을 해제하면 RELEASED와 해제 시각이 남는다")
    void release() {
        CouponUsage usage = reserved();

        usage.release(NOW.plusMinutes(16));

        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.RELEASED);
        assertThat(usage.getReleasedAt()).isEqualTo(NOW.plusMinutes(16));
    }

    @Test
    @DisplayName("예약이 아닌 기록을 사용하거나 해제하면 코드 오류(IllegalStateException)다")
    void notReserved() {
        CouponUsage used = reserved();
        used.use(NOW);
        CouponUsage released = reserved();
        released.release(NOW);

        assertThatThrownBy(() -> used.release(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> released.use(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("1인 1회 판단에는 예약과 사용만 들어가고 해제는 빠진다")
    void occupiedStatuses() {
        assertThat(CouponUsage.OCCUPIED_STATUSES)
                .containsExactlyInAnyOrder(CouponUsageStatus.RESERVED, CouponUsageStatus.USED);
    }
}
