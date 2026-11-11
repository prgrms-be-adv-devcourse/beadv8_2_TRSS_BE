package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 발행 입력값
 *
 * @param totalQuantity 수량 제한이 없으면 null
 */
public record ProductCouponIssueCommand(
        Long partnershipId,
        Long productId,
        String name,
        ProductCouponDiscount discount,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        boolean quantityLimited,
        Integer totalQuantity
) {
}
