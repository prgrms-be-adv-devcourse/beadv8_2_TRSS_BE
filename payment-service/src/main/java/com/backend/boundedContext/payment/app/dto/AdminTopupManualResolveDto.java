package com.backend.boundedContext.payment.app.dto;

import com.backend.boundedContext.payment.domain.TopupManualResolveAction;
import com.backend.boundedContext.payment.domain.TopupStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 충전 수동 확인 정리 결과
@Schema(description = "관리자 충전 수동 확인 정리 결과")
public record AdminTopupManualResolveDto(
        @Schema(description = "충전 건 ID", example = "14")
        Long topupId,

        @Schema(description = "정리 방법", example = "CONFIRM_CANCEL")
        TopupManualResolveAction action,

        @Schema(description = """
                정리한 뒤 충전 상태
                - CONFIRM_CANCEL: 전부 취소면 CANCELED, 일부면 DONE
                - RESTORE: DONE
                - CONFIRM_METHOD_CANCEL: FAILED_METHOD_NOT_ALLOWED
                - RETRY_METHOD_CANCEL: 취소되면 FAILED_METHOD_NOT_ALLOWED, 끝나지 않으면 METHOD_CANCELING (대사가 다시 확인)""", example = "DONE")
        TopupStatus status
) {}
