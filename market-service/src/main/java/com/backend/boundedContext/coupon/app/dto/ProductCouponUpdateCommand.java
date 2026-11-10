package com.backend.boundedContext.coupon.app.dto;

import com.backend.boundedContext.coupon.domain.DiscountType;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 수정 입력값. 보낸 항목만 바꾸고, 보내지 않은 항목(null)은 그대로 둔다.
 * 시작 전에는 모든 항목을 바꿀 수 있고, 시작 후에는 validUntil(연장)만 보낼 수 있다.
 *
 * @param discountType      할인 방식을 바꾸면 discountValue도 함께 보내야 하고, RATE면 maxDiscountAmount도 보내야 한다
 * @param quantityLimited   수량 제한 여부를 바꾸면 totalQuantity도 함께 판단한다(제한이면 필수, 제한 없음이면 보내지 않음)
 */
public record ProductCouponUpdateCommand(
        String name,
        DiscountType discountType,
        Long discountValue,
        Long maxDiscountAmount,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        Boolean quantityLimited,
        Integer totalQuantity
) {
    // 시작 후에도 바꿀 수 있는 종료 일시 말고 다른 항목을 보냈는지
    public boolean hasFieldsOtherThanValidUntil() {
        return name != null || discountType != null || discountValue != null || maxDiscountAmount != null
                || validFrom != null || quantityLimited != null || totalQuantity != null;
    }
}
