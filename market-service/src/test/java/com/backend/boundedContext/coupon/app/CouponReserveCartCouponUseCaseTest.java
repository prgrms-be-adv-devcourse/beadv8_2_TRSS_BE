package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponReserveCartCouponUseCaseTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long COUPON_ID = 10L;
    private static final Long ORDER_ID = 500L;

    @Mock CouponSupport couponSupport;
    @Mock CouponRepository couponRepository;
    @Mock CouponUsageRepository couponUsageRepository;

    CouponReserveCartCouponUseCase useCase;
    LocalDateTime now;

    @BeforeEach
    void setUp() {
        useCase = new CouponReserveCartCouponUseCase(couponSupport, couponRepository, couponUsageRepository);
        now = LocalDateTime.now();
    }

    private Coupon givenCoupon(Integer totalQuantity) {
        Coupon coupon = CouponFixtures.withId(CouponFixtures.activeCartCoupon(totalQuantity), COUPON_ID);
        given(couponSupport.getCouponForUpdate(COUPON_ID)).willReturn(coupon);
        return coupon;
    }

    private void givenNotUsed() {
        given(couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(COUPON_ID, MEMBER_ID, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(false);
    }

    @Test
    @DisplayName("수량 제한이 없으면 수량을 건드리지 않고, 62,000원 주문에 5,000원으로 예약한 기록을 바로 저장한다")
    void reserveUnlimited() {
        givenCoupon(null);
        givenNotUsed();

        long discount = useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 62_000, now);

        assertThat(discount).isEqualTo(5_000);
        ArgumentCaptor<CouponUsage> captor = ArgumentCaptor.forClass(CouponUsage.class);
        verify(couponUsageRepository).saveAndFlush(captor.capture());
        CouponUsage usage = captor.getValue();
        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.RESERVED);
        assertThat(usage.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(usage.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(usage.getOrderItemId()).isNull();
        assertThat(usage.getDiscountAmount()).isEqualTo(5_000);
        verify(couponRepository, never()).decreaseRemainingQuantity(anyLong());
    }

    @Test
    @DisplayName("수량 제한이 있으면 남은 수량을 조건부로 줄인 뒤 예약한다")
    void reserveLimited() {
        givenCoupon(10);
        givenNotUsed();
        given(couponRepository.decreaseRemainingQuantity(COUPON_ID)).willReturn(1);

        long discount = useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 30_000, now);

        assertThat(discount).isEqualTo(3_000);
        verify(couponRepository).decreaseRemainingQuantity(COUPON_ID);
        verify(couponUsageRepository).saveAndFlush(any(CouponUsage.class));
    }

    @Test
    @DisplayName("남은 수량이 없어 줄이지 못하면 409-COUPON_SOLD_OUT이고 기록을 남기지 않는다")
    void soldOut() {
        givenCoupon(10);
        givenNotUsed();
        given(couponRepository.decreaseRemainingQuantity(COUPON_ID)).willReturn(0);

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 50_000, now))
                .extracting("resultCode").isEqualTo("409-COUPON_SOLD_OUT");
        verify(couponUsageRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("상품 쿠폰을 장바구니 쿠폰으로 쓰면 400-COUPON_NOT_APPLICABLE")
    void productCoupon() {
        Coupon coupon = givenCoupon(null);
        ReflectionTestUtils.setField(coupon, "couponType", CouponType.PRODUCT);

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 50_000, now))
                .extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("중지됐거나 기간 밖이면 409-COUPON_NOT_ACTIVE")
    void notActive() {
        Coupon coupon = givenCoupon(null);
        coupon.stop(CouponStoppedBy.ADMIN, "중지", now);

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 50_000, now))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 50_000, now.plusDays(2)))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
    }

    @Test
    @DisplayName("이미 예약했거나 사용했으면 409-COUPON_ALREADY_USED이고 수량을 줄이지 않는다")
    void alreadyUsed() {
        givenCoupon(10);
        given(couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(COUPON_ID, MEMBER_ID, CouponUsage.OCCUPIED_STATUSES))
                .willReturn(true);

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 50_000, now))
                .extracting("resultCode").isEqualTo("409-COUPON_ALREADY_USED");
        verify(couponRepository, never()).decreaseRemainingQuantity(anyLong());
    }

    @Test
    @DisplayName("주문 금액이 가장 낮은 구간에 못 미치면 400-COUPON_MIN_ORDER_NOT_MET이고 수량을 줄이지 않는다")
    void minOrderNotMet() {
        givenCoupon(10);
        givenNotUsed();

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 29_999, now))
                .extracting("resultCode").isEqualTo("400-COUPON_MIN_ORDER_NOT_MET");
        verify(couponRepository, never()).decreaseRemainingQuantity(anyLong());
    }

    @Test
    @DisplayName("검사 뒤 같은 회원의 다른 주문이 먼저 예약해 유니크 인덱스에 걸리면 409-COUPON_ALREADY_USED")
    void uniqueViolation() {
        givenCoupon(null);
        givenNotUsed();
        given(couponUsageRepository.saveAndFlush(any(CouponUsage.class)))
                .willThrow(new DataIntegrityViolationException("uk_coupon_usage_member_active"));

        assertThatThrownBy(() -> useCase.reserve(MEMBER_ID, COUPON_ID, ORDER_ID, 50_000, now))
                .extracting("resultCode").isEqualTo("409-COUPON_ALREADY_USED");
    }
}
