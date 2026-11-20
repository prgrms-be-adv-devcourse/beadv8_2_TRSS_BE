package com.backend.boundedContext.coupon.in.dto;

import com.backend.boundedContext.coupon.app.dto.ProductCouponUpdateCommand;
import com.backend.boundedContext.coupon.domain.DiscountType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 수정 요청. 보낸 항목만 바꾼다.
 * 시작 전에는 모든 항목을, 시작 후에는 validUntil(연장)만 보낼 수 있다.
 *
 * @param discountType  바꾸면 discountValue도 함께 보내야 하고, RATE면 maxDiscountAmount도 보내야 한다
 * @param totalQuantity quantityLimited를 true로 보내면 필수, false로 보내면 보내지 않는다
 */
public record ProductCouponUpdateRequest(
        // 보내지 않으면(null) 그대로 두고, 보냈다면 공백만으로는 바꿀 수 없다(발행 요청의 @NotBlank와 같은 기준)
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "쿠폰명은 공백일 수 없습니다.") String name,
        DiscountType discountType,
        @Positive Long discountValue,
        @Positive Long maxDiscountAmount,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        Boolean quantityLimited,
        @Positive @Max(1_000_000) Integer totalQuantity
) {
    @AssertTrue(message = "바꿀 항목을 하나 이상 보내야 합니다.")
    public boolean isAnyFieldPresent() {
        return name != null || discountType != null || discountValue != null || maxDiscountAmount != null
                || validFrom != null || validUntil != null || quantityLimited != null || totalQuantity != null;
    }

    @AssertTrue(message = "할인 방식을 바꾸면 할인값도 보내야 합니다.")
    public boolean isDiscountValuePresentWithType() {
        return discountType == null || discountValue != null;
    }

    @AssertTrue(message = "수량 제한 여부를 true로 보내면 totalQuantity도 보내고, false로 보내면 보내지 않아야 합니다.")
    public boolean isTotalQuantityConsistent() {
        if (quantityLimited == null) {
            return true;
        }
        return quantityLimited == (totalQuantity != null);
    }

    public ProductCouponUpdateCommand toCommand() {
        return new ProductCouponUpdateCommand(name, discountType, discountValue, maxDiscountAmount,
                validFrom, validUntil, quantityLimited, totalQuantity);
    }
}
