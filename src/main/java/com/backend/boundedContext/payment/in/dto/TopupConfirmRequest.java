package com.backend.boundedContext.payment.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 충전 승인 요청. 토스 결제 성공 리다이렉트 URL의 쿼리 파라미터(paymentKey, orderId, amount)를 그대로 넘김
 */
public record TopupConfirmRequest(
        @Schema(description = "토스 결제키", example = "tgen_20261013100000abcd1")
        @NotBlank
        String paymentKey,

        @Schema(description = "충전 준비 때 받은 주문번호", example = "TOPUP-123e4567-e89b-12d3-a456-426614174000")
        @NotBlank
        String orderId,

        @Schema(description = "결제 금액(원)", example = "20000")
        @NotNull
        Long amount
) {}
