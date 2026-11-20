package com.backend.boundedContext.payment.in.dto;

import com.backend.boundedContext.payment.domain.TopupManualResolveAction;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 관리자 충전 수동 확인 정리 요청
public record TopupManualResolveRequest(
        @Schema(description = """
                정리 방법 (충전 상태에 맞는 것만)
                - CANCELING: CONFIRM_CANCEL(취소 확정), RESTORE(되돌림, 잔액·남은 금액 복원)
                - METHOD_CANCELING: CONFIRM_METHOD_CANCEL(PG에서 전액 취소된 것을 확인해 확정), RETRY_METHOD_CANCEL(PG 전액 취소 다시 요청)""", example = "CONFIRM_CANCEL")
        @NotNull
        TopupManualResolveAction action
) {}
