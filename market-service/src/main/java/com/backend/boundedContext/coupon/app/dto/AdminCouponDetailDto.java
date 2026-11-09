package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponIssuerType;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.out.CouponUsageStat;
import com.backend.global.page.PageResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 쿠폰 상세
 *
 * @param status             조회 시점의 화면 상태 (SCHEDULED, ACTIVE, ENDED, STOPPED)
 * @param tiers              장바구니 쿠폰의 할인 구간 (최소 주문 금액 오름차순). 상품 쿠폰은 빈 목록
 * @param stoppedBy          중지 주체. 중지되지 않았으면 null
 * @param stopReason         중지 사유. 중지되지 않았으면 null
 * @param stoppedAt          중지 시각. 중지되지 않았으면 null
 * @param usedCount          사용(USED) 수
 * @param usedDiscountAmount 사용(USED)된 할인 합계
 * @param usages             사용 내역 (최신순, page와 size로 나눠 조회)
 */
public record AdminCouponDetailDto(
        Long couponId,
        CouponType couponType,
        CouponIssuerType issuerType,
        Long issuerId,
        String name,
        CouponDisplayStatus status,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        boolean quantityLimited,
        Integer totalQuantity,
        Integer remainingQuantity,
        List<CartCouponTierDto> tiers,
        CouponStoppedBy stoppedBy,
        String stopReason,
        LocalDateTime stoppedAt,
        long usedCount,
        long usedDiscountAmount,
        LocalDateTime createdAt,
        PageResponse<AdminCouponUsageDto> usages
) {
    public static AdminCouponDetailDto of(Coupon coupon, LocalDateTime now, CouponUsageStat usedStat,
                                          PageResponse<AdminCouponUsageDto> usages) {
        return new AdminCouponDetailDto(
                coupon.getId(),
                coupon.getCouponType(),
                coupon.getIssuerType(),
                coupon.getIssuerId(),
                coupon.getName(),
                coupon.displayStatus(now),
                coupon.getValidFrom(),
                coupon.getValidUntil(),
                coupon.isQuantityLimited(),
                coupon.getTotalQuantity(),
                coupon.getRemainingQuantity(),
                coupon.getTiers().stream().map(CartCouponTierDto::from).toList(),
                coupon.getStoppedBy(),
                coupon.getStopReason(),
                coupon.getStoppedAt(),
                usedStat.count(),
                usedStat.discountAmount(),
                coupon.getCreatedAt(),
                usages
        );
    }
}
