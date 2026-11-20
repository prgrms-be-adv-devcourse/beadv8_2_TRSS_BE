package com.backend.boundedContext.member.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 관리자가 인플루언서를 반려할 때의 사유. 필수이며 상태 이력에 남고 신청자에게 보인다
public record InfluencerReasonRequest(
        @Schema(example = "SNS 계정에서 본인 확인이 어려워요. 프로필에 팔레트 활동명을 적어 주세요.")
        @NotBlank @Size(max = 255)
        String reason
) {
}
