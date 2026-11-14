package com.backend.boundedContext.coupon.in.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 협약 요청
 *
 * @param influencerId   인플루언서 ID(influencer.id)
 * @param commissionRate 인플루언서 수수료율(%), 15~30 정수
 * @param message        요청 메시지 (선택, 최대 500자)
 */
public record PartnershipRequestRequest(
        @NotNull @Positive Long influencerId,
        @NotNull @Min(15) @Max(30) Integer commissionRate,
        @Size(max = 500) String message
) {
}
