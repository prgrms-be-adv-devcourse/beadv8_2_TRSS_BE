package com.backend.boundedContext.member.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InfluencerApplyRequest(
        @Schema(example = "데일리 뷰티 민지")
        @NotBlank @Size(max = 30)
        String activityName,

        @Schema(example = "https://instagram.com/daily.beauty")
        @NotBlank @Size(max = 500)
        @Pattern(regexp = "^\\s*https?://\\S+\\.\\S+\\s*$", message = "SNS 계정 주소는 http:// 또는 https://로 시작해야 합니다.")
        String snsUrl,

        @Schema(example = "지성·트러블 피부를 위한 스킨케어 루틴을 주로 올려요.")
        @NotBlank @Size(max = 500)
        String introduction
) {
}
