package com.backend.boundedContext.coupon.in.dto;

import com.backend.boundedContext.coupon.app.dto.CartCouponCreateCommand;
import com.backend.boundedContext.coupon.domain.CartCouponTierSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 장바구니 쿠폰 등록 요청
 *
 * @param name            쿠폰명 (최대 100자)
 * @param tiers           할인 구간 1~10개. 순서는 상관없고 최소 주문 금액 순으로 저장한다
 * @param validFrom       시작 일시 (포함, 예: 2026-11-08T00:00:00)
 * @param validUntil      종료 일시 (포함하지 않음, 예: 2026-11-09T18:00:00이면 17시 59분 59초까지 사용 가능)
 * @param quantityLimited 수량 제한 여부
 * @param totalQuantity   총 수량 (1~1,000,000). 수량 제한이 있을 때만 보낸다
 */
public record CartCouponCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Size(min = 1, max = 10) List<@NotNull @Valid TierRequest> tiers,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validUntil,
        @NotNull Boolean quantityLimited,
        @Positive @Max(1_000_000) Integer totalQuantity
) {
    @AssertTrue(message = "수량 제한이 있으면 totalQuantity를 보내고, 없으면 보내지 않아야 합니다.")
    public boolean isTotalQuantityConsistent() {
        if (quantityLimited == null) {
            return true;   // @NotNull이 따로 알려 준다
        }
        return quantityLimited == (totalQuantity != null);
    }

    public CartCouponCreateCommand toCommand() {
        return new CartCouponCreateCommand(
                name,
                tiers.stream().map(TierRequest::toSpec).toList(),
                quantityLimited,
                totalQuantity,
                validFrom,
                validUntil
        );
    }

    /**
     * 할인 구간
     *
     * @param minOrderAmount 최소 주문 금액 (이 금액 이상이면 적용)
     * @param discountAmount 할인액 (최소 주문 금액보다 작아야 함)
     */
    public record TierRequest(
            @NotNull @Positive Long minOrderAmount,
            @NotNull @Positive Long discountAmount
    ) {
        CartCouponTierSpec toSpec() {
            return new CartCouponTierSpec(minOrderAmount, discountAmount);
        }
    }
}
