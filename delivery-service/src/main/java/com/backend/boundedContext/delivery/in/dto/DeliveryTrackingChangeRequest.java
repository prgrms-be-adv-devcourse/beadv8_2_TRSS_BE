package com.backend.boundedContext.delivery.in.dto;

import com.backend.boundedContext.delivery.domain.Courier;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 송장 수정 요청
public record DeliveryTrackingChangeRequest(
        @Schema(description = "택배사 (CJ·POST·HANJIN·LOTTE)", example = "POST")
        @NotNull Courier courier,
        @Schema(description = "송장번호 (하이픈·공백 포함 40자 이내, 지운 뒤 영문·숫자 8~30자)", example = "6896-1234-5678")
        @NotBlank @Size(max = 40) String trackingNumber
) { }
