package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.SellerCouponMonthlyStatsDto;
import com.backend.boundedContext.coupon.out.CouponSellerMonthlyUsage;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 판매자 상품 쿠폰의 한 달 사용 집계
 * 쿠폰 목록의 쿠폰별 사용 수는 누적이라, 화면의 "이번 달 사용, 할인 총액"은 사용 시각(usedAt)으로 따로 센다.
 * 달의 경계는 서버 시간(한국 시간) 기준 [그달 1일 00:00, 다음 달 1일 00:00)이다
 */
@Component
@RequiredArgsConstructor
public class CouponGetSellerCouponStatsUseCase {

    private final CouponSellerPort couponSellerPort;
    private final CouponUsageRepository couponUsageRepository;

    public SellerCouponMonthlyStatsDto getMonthlyForSeller(Long memberId, YearMonth month) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        CouponSellerMonthlyUsage usage = couponUsageRepository.sumUsedBySellerBetween(sellerId, from, to);
        long usedCount = usage == null || usage.usedCount() == null ? 0L : usage.usedCount();
        long usedDiscountAmount = usage == null || usage.usedDiscountAmount() == null ? 0L : usage.usedDiscountAmount();
        return new SellerCouponMonthlyStatsDto(month.toString(), usedCount, usedDiscountAmount);
    }
}
