package com.backend.boundedContext.member.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자가 판매자 상태를 바꿀 때의 사유 (입점 반려·철회 거절·제재). 필수이며 상태 이력에 남는다.
 */
public record SellerReasonRequest(
        @Schema(example = "사업자등록증의 상호와 신청 상호가 다릅니다.")
        @NotBlank @Size(max = 255)
        String reason
) {
}
