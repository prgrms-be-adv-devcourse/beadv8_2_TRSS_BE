package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponUseAndReleaseReservedCouponsUseCaseTest {

    private static final Long ORDER_ID = 500L;
    private static final LocalDateTime NOW = LocalDateTime.now();

    @Mock CouponUsageRepository couponUsageRepository;
    @Mock CouponRepository couponRepository;

    private CouponUsage reserved(Coupon coupon, Long usageId) {
        CouponUsage usage = CouponUsage.reserve(coupon, 7L, ORDER_ID, null, 3_000, NOW);
        ReflectionTestUtils.setField(usage, "id", usageId);
        return usage;
    }

    @Test
    @DisplayName("결제 성공: 아직 RESERVED이고 쿠폰을 지금 쓸 수 있는 기록만 USED로 바꾸고, 바뀐 수를 돌려준다")
    void use() {
        given(couponUsageRepository.countByOrderIdAndStatus(ORDER_ID, CouponUsageStatus.RESERVED)).willReturn(1L);
        given(couponUsageRepository.markUsedIfReservedAndCouponUsable(ORDER_ID, NOW)).willReturn(1);

        int count = new CouponUseReservedCouponsUseCase(couponUsageRepository).use(ORDER_ID, NOW);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 성공: 예약 뒤 중지됐거나 기간이 끝난 쿠폰이 있어 예약 수보다 적게 바뀌면 409-COUPON_NOT_ACTIVE (결제와 함께 롤백)")
    void use_couponStoppedAfterReserve() {
        given(couponUsageRepository.countByOrderIdAndStatus(ORDER_ID, CouponUsageStatus.RESERVED)).willReturn(2L);
        given(couponUsageRepository.markUsedIfReservedAndCouponUsable(ORDER_ID, NOW)).willReturn(1);

        assertThatThrownBy(() -> new CouponUseReservedCouponsUseCase(couponUsageRepository).use(ORDER_ID, NOW))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
    }

    @Test
    @DisplayName("결제 전 취소: 기록마다 조건부 UPDATE로 해제하고, 수량 제한 쿠폰만 남은 수량을 되돌린다")
    void release() {
        CouponUsage limited = reserved(CouponFixtures.withId(CouponFixtures.activeCartCoupon(10), 1L), 11L);
        CouponUsage unlimited = reserved(CouponFixtures.withId(CouponFixtures.activeCartCoupon(null), 2L), 12L);
        given(couponUsageRepository.findAllWithCouponByOrderIdAndStatus(ORDER_ID, CouponUsageStatus.RESERVED))
                .willReturn(List.of(limited, unlimited));
        given(couponUsageRepository.markReleasedIfReserved(11L, NOW)).willReturn(1);
        given(couponUsageRepository.markReleasedIfReserved(12L, NOW)).willReturn(1);

        int count = new CouponReleaseReservedCouponsUseCase(couponUsageRepository, couponRepository).release(ORDER_ID, NOW);

        assertThat(count).isEqualTo(2);
        verify(couponRepository).increaseRemainingQuantity(1L);
        verify(couponRepository, never()).increaseRemainingQuantity(2L);
    }

    @Test
    @DisplayName("해제: 읽은 뒤 다른 요청(동시 취소·만료·결제 성공)이 먼저 처리한 기록은 건너뛰고 수량도 되돌리지 않는다")
    void release_alreadyProcessedByOther() {
        CouponUsage usage = reserved(CouponFixtures.withId(CouponFixtures.activeCartCoupon(10), 1L), 11L);
        given(couponUsageRepository.findAllWithCouponByOrderIdAndStatus(ORDER_ID, CouponUsageStatus.RESERVED))
                .willReturn(List.of(usage));
        given(couponUsageRepository.markReleasedIfReserved(11L, NOW)).willReturn(0);

        int count = new CouponReleaseReservedCouponsUseCase(couponUsageRepository, couponRepository).release(ORDER_ID, NOW);

        assertThat(count).isZero();
        verify(couponRepository, never()).increaseRemainingQuantity(anyLong());
    }

    @Test
    @DisplayName("예약이 없거나 이미 처리됐으면 아무것도 하지 않는다 (같은 주문에 다시 불러도 결과가 같다)")
    void nothingReserved() {
        given(couponUsageRepository.findAllWithCouponByOrderIdAndStatus(ORDER_ID, CouponUsageStatus.RESERVED))
                .willReturn(List.of());

        int count = new CouponReleaseReservedCouponsUseCase(couponUsageRepository, couponRepository).release(ORDER_ID, NOW);

        assertThat(count).isZero();
        verify(couponRepository, never()).increaseRemainingQuantity(anyLong());
    }
}
