package com.backend.boundedContext.coupon.in.dto;

import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueCommand;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 발행 요청
 *
 * @param discountValue     FIXED면 할인액(원), RATE면 할인율(1~100)
 * @param maxDiscountAmount RATE일 때 최대 할인액(필수). FIXED면 보내지 않는다
 * @param validFrom         시작 일시 (포함)
 * @param validUntil        종료 일시 (포함하지 않음)
 * @param totalQuantity     총 수량 (1~1,000,000). 수량 제한이 있을 때만 보낸다
 */
public record ProductCouponIssueRequest(
        @NotNull @Positive Long partnershipId,
        @NotNull @Positive Long productId,
        @NotBlank @Size(max = 100) String name,
        @NotNull DiscountType discountType,
        @NotNull @Positive Long discountValue,
        @Positive Long maxDiscountAmount,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validUntil,
        @NotNull Boolean quantityLimited,
        @Positive @Max(1_000_000) Integer totalQuantity
) {
    @AssertTrue(message = "정률(RATE)은 할인율 1~100과 최대 할인액을 보내야 하고, 정액(FIXED)은 최대 할인액을 보내지 않아야 합니다.")
    public boolean isDiscountConsistent() {
        if (discountType == null || discountValue == null) {
            return true;   // @NotNull이 따로 알려 준다
        }
        return switch (discountType) {
            case FIXED -> maxDiscountAmount == null;
            case RATE -> discountValue <= 100 && maxDiscountAmount != null;
        };
    }

    @AssertTrue(message = "수량 제한이 있으면 totalQuantity를 보내고, 없으면 보내지 않아야 합니다.")
    public boolean isTotalQuantityConsistent() {
        if (quantityLimited == null) {
            return true;   // @NotNull이 따로 알려 준다
        }
        return quantityLimited == (totalQuantity != null);
    }

    public ProductCouponIssueCommand toCommand() {
        return new ProductCouponIssueCommand(partnershipId, productId, name,
                new ProductCouponDiscount(discountType, discountValue, maxDiscountAmount),
                validFrom, validUntil, quantityLimited, totalQuantity);
    }
}
