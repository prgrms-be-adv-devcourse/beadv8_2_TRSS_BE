package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 판매 중지·중지 요청 반려 사유
 *
 * @param reason 사유 (필수, 최대 300자)
 */
public record ProductReasonRequest(
        @NotBlank @Size(max = 300) String reason
) {
}
