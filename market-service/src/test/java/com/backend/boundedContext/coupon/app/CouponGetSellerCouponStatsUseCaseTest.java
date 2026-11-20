package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.SellerCouponMonthlyStatsDto;
import com.backend.boundedContext.coupon.out.CouponSellerMonthlyUsage;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CouponGetSellerCouponStatsUseCaseTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long SELLER_ID = 20L;

    @Mock CouponSellerPort couponSellerPort;
    @Mock CouponUsageRepository couponUsageRepository;
    @InjectMocks CouponGetSellerCouponStatsUseCase useCase;

    @Test
    @DisplayName("그달 1일 00:00부터 다음 달 1일 00:00 전까지 사용된 판매자 쿠폰을 센다")
    void monthRange() {
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(SELLER_ID);
        given(couponUsageRepository.sumUsedBySellerBetween(SELLER_ID,
                LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 12, 1, 0, 0)))
                .willReturn(new CouponSellerMonthlyUsage(36L, 170_600L));

        SellerCouponMonthlyStatsDto stats = useCase.getMonthlyForSeller(MEMBER_ID, YearMonth.of(2026, 11));

        assertThat(stats.month()).isEqualTo("2026-11");
        assertThat(stats.usedCount()).isEqualTo(36L);
        assertThat(stats.usedDiscountAmount()).isEqualTo(170_600L);
    }

    @Test
    @DisplayName("12월은 다음 해 1월 1일 전까지 센다")
    void decemberRange() {
        given(couponSellerPort.getApprovedSellerId(MEMBER_ID)).willReturn(SELLER_ID);
        given(couponUsageRepository.sumUsedBySellerBetween(SELLER_ID,
                LocalDateTime.of(2026, 12, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0)))
                .willReturn(new CouponSellerMonthlyUsage(0L, 0L));

        SellerCouponMonthlyStatsDto stats = useCase.getMonthlyForSeller(MEMBER_ID, YearMonth.of(2026, 12));

        assertThat(stats.usedCount()).isZero();
        assertThat(stats.usedDiscountAmount()).isZero();
    }
}
