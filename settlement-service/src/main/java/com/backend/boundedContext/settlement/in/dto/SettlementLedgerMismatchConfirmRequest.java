package com.backend.boundedContext.settlement.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 원장 재대조 불일치 확인 요청
 *
 * 사유는 필수이며 처리 이력(VERIFY, ADMIN)의 admin_reason에 남는다 */
public record SettlementLedgerMismatchConfirmRequest(
        @NotBlank(message = "확인 사유를 입력해 주세요.")
        @Size(max = 300, message = "사유는 300자 이하로 입력해 주세요.")
        String reason
) {
}
