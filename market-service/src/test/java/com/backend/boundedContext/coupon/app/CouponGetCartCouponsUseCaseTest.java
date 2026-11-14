package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.app.dto.CartCouponDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.out.CouponOrderPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CouponGetCartCouponsUseCaseTest {

    private static final Long MEMBER_ID = 7L;

    @Mock CouponRepository couponRepository;
    @Mock CouponUsageRepository couponUsageRepository;
    @Mock CouponOrderPort couponOrderPort;

    @InjectMocks CouponGetCartCouponsUseCase useCase;

    private Coupon unused;
    private Coupon used;
    private Coupon soldOut;

    private void givenCoupons() {
        unused = CouponFixtures.withId(CouponFixtures.activeCartCoupon(null), 1L);
        used = CouponFixtures.withId(CouponFixtures.activeCartCoupon(null), 2L);
        soldOut = CouponFixtures.withId(CouponFixtures.activeCartCoupon(10), 3L);
        ReflectionTestUtils.setField(soldOut, "remainingQuantity", 0);

        given(couponRepository.findAll(ArgumentMatchers.<Specification<Coupon>>any(), any(Sort.class)))
                .willReturn(List.of(unused, used, soldOut));
        given(couponUsageRepository.findCouponIdsByMember(eq(MEMBER_ID), eq(List.of(1L, 2L, 3L)), eq(CouponUsage.OCCUPIED_STATUSES)))
                .willReturn(List.of(2L));
    }

    @Test
    @DisplayName("cartItemIds가 없으면 금액을 계산하지 않고, 쿠폰마다 사용 여부와 소진 여부만 알려 준다")
    void withoutCartItems() {
        givenCoupons();

        CartCouponsDto result = useCase.getCartCoupons(MEMBER_ID, null);

        assertThat(result.orderAmount()).isNull();
        assertThat(result.coupons()).extracting(CartCouponDto::couponId).containsExactly(1L, 2L, 3L);
        assertThat(result.coupons()).extracting(CartCouponDto::used).containsExactly(false, true, false);
        assertThat(result.coupons()).extracting(CartCouponDto::soldOut).containsExactly(false, false, true);
        assertThat(result.coupons()).extracting(CartCouponDto::expectedDiscountAmount).containsOnlyNulls();
        assertThat(result.coupons()).extracting(CartCouponDto::applicable).containsOnlyNulls();
        assertThat(result.coupons().getFirst().tiers()).hasSize(3);
        verifyNoInteractions(couponOrderPort);
    }

    @Test
    @DisplayName("cartItemIds를 주면 항목 금액(62,000원)으로 할인액을 계산하고, 이미 썼거나 소진된 쿠폰은 0원이다")
    void withCartItems() {
        givenCoupons();
        given(couponOrderPort.getCartItemsAmount(MEMBER_ID, List.of(11L, 12L))).willReturn(62_000L);

        CartCouponsDto result = useCase.getCartCoupons(MEMBER_ID, List.of(11L, 12L));

        assertThat(result.orderAmount()).isEqualTo(62_000L);
        assertThat(result.coupons()).extracting(CartCouponDto::expectedDiscountAmount).containsExactly(5_000L, 0L, 0L);
        assertThat(result.coupons()).extracting(CartCouponDto::applicable).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("주문 금액이 가장 낮은 구간에 못 미치면 할인액 0원, 적용 불가다")
    void belowMinOrder() {
        givenCoupons();
        given(couponOrderPort.getCartItemsAmount(MEMBER_ID, List.of(11L))).willReturn(29_000L);

        CartCouponsDto result = useCase.getCartCoupons(MEMBER_ID, List.of(11L));

        assertThat(result.coupons().getFirst().expectedDiscountAmount()).isZero();
        assertThat(result.coupons().getFirst().applicable()).isFalse();
    }

    @Test
    @DisplayName("사용 가능한 쿠폰이 없으면 사용 기록을 조회하지 않는다 (빈 IN 절 방지)")
    void noCoupons() {
        given(couponRepository.findAll(ArgumentMatchers.<Specification<Coupon>>any(), any(Sort.class)))
                .willReturn(List.of());

        CartCouponsDto result = useCase.getCartCoupons(MEMBER_ID, List.of());

        assertThat(result.coupons()).isEmpty();
        assertThat(result.orderAmount()).isNull();
        verify(couponUsageRepository, never()).findCouponIdsByMember(anyLong(), anyCollection(), anyCollection());
        verifyNoInteractions(couponOrderPort);
    }
}
