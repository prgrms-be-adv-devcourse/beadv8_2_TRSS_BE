package com.backend.boundedContext.delivery.in.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

// 관리자 수동 배송 완료 요청
public record DeliveryCompleteRequest(
        @Schema(description = "관리자가 확인한 배송 완료 시각(KST). 미래·발송 시각 이전은 안 됨", example = "2026-11-14T15:30:00")
        @NotNull @PastOrPresent LocalDateTime deliveredAt,
        @Schema(description = "수동 완료 사유 (300자 이내)", example = "택배사 조회가 EXCEPTION에서 멈춰 구매자 수령을 전화로 확인함")
        @NotBlank @Size(max = 300) String reason
) { }
