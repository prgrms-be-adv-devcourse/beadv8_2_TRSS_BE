package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.out.CouponUsageStat;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 (판매자 목록, 상세와 인플루언서 목록 공통)
 *
 * @param status             조회 시점의 화면 상태 (SCHEDULED, ACTIVE, ENDED, STOPPED)
 * @param businessName       판매자 상호명. 조회하지 못하면 null
 * @param activityName       인플루언서 활동명. 조회하지 못하면 null
 * @param productName        대상 상품명. 조회하지 못하면 null
 * @param discountValue      FIXED면 할인액(원), RATE면 할인율(%)
 * @param maxDiscountAmount  RATE일 때 최대 할인액. FIXED면 null
 * @param totalQuantity      수량 제한이 없으면 null
 * @param remainingQuantity  수량 제한이 없으면 null. 예약된 수량도 빠진 값
 * @param usedCount          사용(USED) 수. 예약 중인 것은 세지 않는다
 * @param usedDiscountAmount 사용(USED)된 할인 합계
 * @param stoppedBy          중지 주체. 중지되지 않았으면 null
 * @param stopReason         중지 사유. 판매자 중지거나 중지되지 않았으면 null
 */
public record ProductCouponDto(
        Long couponId,
        String name,
        CouponDisplayStatus status,
        String code,
        Long partnershipId,
        Long sellerId,
        String businessName,
        Long influencerId,
        String activityName,
        Long productId,
        String productName,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        boolean quantityLimited,
        Integer totalQuantity,
        Integer remainingQuantity,
        long usedCount,
        long usedDiscountAmount,
        CouponStoppedBy stoppedBy,
        String stopReason,
        LocalDateTime stoppedAt,
        LocalDateTime createdAt
) {
    public static ProductCouponDto of(ProductCoupon productCoupon, LocalDateTime now, CouponUsageStat usedStat,
                                      String businessName, String activityName, String productName) {
        Coupon coupon = productCoupon.getCoupon();
        return new ProductCouponDto(
                coupon.getId(),
                coupon.getName(),
                coupon.displayStatus(now),
                productCoupon.getCode(),
                productCoupon.getPartnershipId(),
                coupon.getIssuerId(),
                businessName,
                productCoupon.getInfluencerId(),
                activityName,
                productCoupon.getProductId(),
                productName,
                productCoupon.getDiscountType(),
                productCoupon.getDiscountValue(),
                productCoupon.getMaxDiscountAmount(),
                coupon.getValidFrom(),
                coupon.getValidUntil(),
                coupon.isQuantityLimited(),
                coupon.getTotalQuantity(),
                coupon.getRemainingQuantity(),
                usedStat.count(),
                usedStat.discountAmount(),
                coupon.getStoppedBy(),
                coupon.getStopReason(),
                coupon.getStoppedAt(),
                coupon.getCreatedAt()
        );
    }
}
