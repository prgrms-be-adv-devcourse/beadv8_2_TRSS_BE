package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;

import java.time.LocalDateTime;

/**
 * 관리자 쿠폰 상세의 사용 내역 한 줄
 *
 * @param orderItemId 상품 쿠폰이 붙은 주문 항목. 장바구니 쿠폰은 null
 * @param usedAt      사용(결제) 시각. 사용 전이면 null
 * @param releasedAt  예약 해제 시각. 해제되지 않았으면 null
 */
public record AdminCouponUsageDto(
        Long usageId,
        Long memberId,
        Long orderId,
        Long orderItemId,
        long discountAmount,
        CouponUsageStatus status,
        LocalDateTime reservedAt,
        LocalDateTime usedAt,
        LocalDateTime releasedAt
) {
    public static AdminCouponUsageDto from(CouponUsage usage) {
        return new AdminCouponUsageDto(
                usage.getId(),
                usage.getMemberId(),
                usage.getOrderId(),
                usage.getOrderItemId(),
                usage.getDiscountAmount(),
                usage.getStatus(),
                usage.getReservedAt(),
                usage.getUsedAt(),
                usage.getReleasedAt()
        );
    }
}
