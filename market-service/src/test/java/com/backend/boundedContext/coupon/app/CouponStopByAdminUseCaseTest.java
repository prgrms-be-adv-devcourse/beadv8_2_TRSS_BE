package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponStatus;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.out.CouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CouponStopByAdminUseCaseTest {

    @Mock CouponRepository couponRepository;

    @InjectMocks CouponStopByAdminUseCase useCase;

    @Test
    @DisplayName("쿠폰을 잠가 조회한 뒤 관리자 중지로 바꾸고 사유를 남긴다")
    void stop() {
        Coupon coupon = CouponFixtures.withId(CouponFixtures.activeCartCoupon(null), 10L);
        given(couponRepository.findByIdForUpdate(10L)).willReturn(Optional.of(coupon));

        useCase.stop(10L, "부정 사용 의심");

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.STOPPED);
        assertThat(coupon.getStoppedBy()).isEqualTo(CouponStoppedBy.ADMIN);
        assertThat(coupon.getStopReason()).isEqualTo("부정 사용 의심");
        assertThat(coupon.getStoppedAt()).isNotNull();
    }

    @Test
    @DisplayName("없는 쿠폰이면 404-COUPON_NOT_FOUND")
    void notFound() {
        given(couponRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.stop(99L, "사유"))
                .extracting("resultCode").isEqualTo("404-COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("이미 중지된 쿠폰이면 409-COUPON_STATE_INVALID")
    void alreadyStopped() {
        Coupon coupon = CouponFixtures.withId(CouponFixtures.activeCartCoupon(null), 10L);
        given(couponRepository.findByIdForUpdate(10L)).willReturn(Optional.of(coupon));
        useCase.stop(10L, "첫 중지");

        assertThatThrownBy(() -> useCase.stop(10L, "두 번째"))
                .extracting("resultCode").isEqualTo("409-COUPON_STATE_INVALID");
        assertThat(coupon.getStopReason()).isEqualTo("첫 중지");
    }
}
