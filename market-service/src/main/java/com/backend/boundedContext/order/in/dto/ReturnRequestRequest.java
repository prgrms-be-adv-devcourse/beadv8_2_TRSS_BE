package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.ReturnReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반품 요청
 *
 * @param reasonCode 반품 사유 코드 (CHANGE_OF_MIND, DEFECTIVE, WRONG_ITEM, NOT_AS_DESCRIBED, OTHER)
 * @param reason     상세 사유 (최대 300자). reasonCode가 OTHER면 필수
 */
public record ReturnRequestRequest(
        @NotNull ReturnReasonCode reasonCode,
        @Size(max = 300) String reason
) {
}
