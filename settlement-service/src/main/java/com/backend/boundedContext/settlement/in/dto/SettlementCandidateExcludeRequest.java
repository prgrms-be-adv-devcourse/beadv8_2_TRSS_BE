package com.backend.boundedContext.settlement.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 정산 후보 제외 요청
 * 사유는 필수이며 후보 excluded_reason과 처리 이력에 남는다 */
public record SettlementCandidateExcludeRequest(
        @NotBlank(message = "제외 사유를 입력해 주세요.")
        @Size(max = 300, message = "사유는 300자 이하로 입력해 주세요.")
        String reason
) {
}