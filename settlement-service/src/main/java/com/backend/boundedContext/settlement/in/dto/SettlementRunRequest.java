package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 관리자 월 정산 수동 실행 요청
 * settleMonth는 이미 끝난 달이어야 한다 */
public record SettlementRunRequest(
        @NotBlank
        @Pattern(regexp = SettlementPolicy.MONTH_PATTERN, message = "정산월은 yyyy-MM 형식이어야 합니다.")
        String settleMonth
) {
}