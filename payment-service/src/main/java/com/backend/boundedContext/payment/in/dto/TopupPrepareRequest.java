package com.backend.boundedContext.payment.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 충전 준비 요청. 금액 단위·범위는 정책(PaymentPolicy)에서 검사해 400 TOPUP_AMOUNT_INVALID로 응답
 */
public record TopupPrepareRequest(
        @Schema(description = "충전 금액(원). 10,000원 단위, 10,000~1,000,000원", example = "20000")
        @NotNull
        Long amount
) {}
