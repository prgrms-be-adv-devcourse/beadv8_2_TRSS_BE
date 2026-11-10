package com.backend.boundedContext.coupon.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 쿠폰 강제 중지 사유
 *
 * @param reason 사유 (필수, 최대 300자)
 */
public record CouponStopRequest(
        @NotBlank @Size(max = 300) String reason
) {
}
