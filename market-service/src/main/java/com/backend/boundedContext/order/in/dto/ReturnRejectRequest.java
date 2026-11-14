package com.backend.boundedContext.order.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 반품 거절 요청. 거절 사유는 구매자 주문 상세에 보이므로 필수다.
 */
public record ReturnRejectRequest(
        @NotBlank @Size(max = 300) String reason
) {
}
