package com.backend.boundedContext.delivery.in.dto;

import com.backend.boundedContext.delivery.domain.Courier;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 송장 등록 요청
public record DeliveryShipRequest(
        @Schema(description = "택배사 (CJ·POST·HANJIN·LOTTE)", example = "CJ")
        @NotNull Courier courier,
        @Schema(description = "송장번호 (하이픈·공백 포함 40자 이내, 지운 뒤 영문·숫자 8~30자)", example = "123456789012")
        @NotBlank @Size(max = 40) String trackingNumber
) { }
