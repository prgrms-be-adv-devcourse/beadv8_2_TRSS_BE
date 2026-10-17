package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 판매 중지 요청
 *
 * @param reason 중지 사유 (필수, 최대 300자). 관리자가 검토할 때 본다
 */
public record ProductStopRequestCreateRequest(
        @NotBlank @Size(max = 300) String reason
) {
}
