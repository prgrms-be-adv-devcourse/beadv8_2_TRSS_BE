package com.backend.boundedContext.member.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 판매자 철회 요청. 사유는 필수이며 상태 이력에 남는다.
 */
public record SellerWithdrawalRequest(
        @Schema(example = "폐업 예정")
        @NotBlank @Size(max = 255)
        String reason
) {
}
