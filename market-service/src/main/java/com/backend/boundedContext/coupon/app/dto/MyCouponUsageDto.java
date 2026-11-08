package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;

import java.time.LocalDateTime;

/**
 * 내 쿠폰 사용 내역 한 줄
 *
 * @param status     RESERVED(결제 대기 주문에 예약), USED(결제 완료), RELEASED(주문 취소로 해제)
 * @param usedAt     사용(결제) 시각. 사용 전이면 null
 * @param releasedAt 예약 해제 시각. 해제되지 않았으면 null
 */
public record MyCouponUsageDto(
        Long usageId,
        Long couponId,
        String couponName,
        CouponType couponType,
        Long orderId,
        long discountAmount,
        CouponUsageStatus status,
        LocalDateTime reservedAt,
        LocalDateTime usedAt,
        LocalDateTime releasedAt
) {
    public static MyCouponUsageDto from(CouponUsage usage) {
        return new MyCouponUsageDto(
                usage.getId(),
                usage.getCoupon().getId(),
                usage.getCoupon().getName(),
                usage.getCoupon().getCouponType(),
                usage.getOrderId(),
                usage.getDiscountAmount(),
                usage.getStatus(),
                usage.getReservedAt(),
                usage.getUsedAt(),
                usage.getReleasedAt()
        );
    }
}
