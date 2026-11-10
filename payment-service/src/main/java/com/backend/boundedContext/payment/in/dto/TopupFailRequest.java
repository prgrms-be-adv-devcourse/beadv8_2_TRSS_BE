package com.backend.boundedContext.payment.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 충전 실패 기록 요청. 토스 결제 실패 리다이렉트 URL의 쿼리 파라미터(code, message)를 그대로 넘김
 */
public record TopupFailRequest(
        @Schema(description = "토스 오류 코드", example = "PAY_PROCESS_CANCELED")
        @NotBlank @Size(max = 100)
        String code,

        @Schema(description = "토스 오류 메시지", example = "사용자에 의해 결제가 취소되었습니다.")
        @Size(max = 300)
        String message
) {}
