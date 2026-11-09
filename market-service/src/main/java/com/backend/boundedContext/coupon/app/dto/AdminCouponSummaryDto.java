package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponIssuerType;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.out.CouponUsageStat;

import java.time.LocalDateTime;

/**
 * 관리자 쿠폰 목록의 한 줄
 *
 * @param issuerId           SELLER면 판매자 ID(seller.id), ADMIN이면 관리자 회원 ID
 * @param status             조회 시점의 화면 상태 (SCHEDULED, ACTIVE, ENDED, STOPPED)
 * @param totalQuantity      수량 제한이 없으면 null
 * @param remainingQuantity  수량 제한이 없으면 null. 예약된 수량도 빠진 값
 * @param usedCount          사용(USED) 수. 예약 중인 것은 세지 않는다
 * @param usedDiscountAmount 사용(USED)된 할인 합계
 */
public record AdminCouponSummaryDto(
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
        long usedCount,
        long usedDiscountAmount,
        LocalDateTime createdAt
) {
    public static AdminCouponSummaryDto of(Coupon coupon, LocalDateTime now, CouponUsageStat usedStat) {
        return new AdminCouponSummaryDto(
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
                usedStat.count(),
                usedStat.discountAmount(),
                coupon.getCreatedAt()
        );
    }
}
