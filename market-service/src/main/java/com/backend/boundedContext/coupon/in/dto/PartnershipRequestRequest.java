package com.backend.boundedContext.coupon.in.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 협약 요청
 *
 * @param influencerId 인플루언서 ID(influencer.id)
 * @param message      요청 메시지 (선택, 최대 500자)
 */
public record PartnershipRequestRequest(
        @NotNull @Positive Long influencerId,
        @Size(max = 500) String message
) {
}
